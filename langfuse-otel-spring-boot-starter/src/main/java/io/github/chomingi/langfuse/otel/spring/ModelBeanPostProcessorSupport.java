package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shared, fail-safe proxy creation for framework model BeanPostProcessors. */
final class ModelBeanPostProcessorSupport {

    private static final Logger log = LoggerFactory.getLogger(ModelBeanPostProcessorSupport.class);

    private ModelBeanPostProcessorSupport() {
    }

    static boolean isSpringAiModel(Object bean) {
        return SpringAiModelMethodInterceptor.supports(bean);
    }

    static boolean isLangChain4jModel(Object bean) {
        return LangChain4jModelMethodInterceptor.supports(bean);
    }

    static boolean hasSpringAiInstrumentation(Object bean) {
        return containsAdvice(bean, SpringAiModelMethodInterceptor.class);
    }

    static boolean hasLangChain4jInstrumentation(Object bean) {
        return containsAdvice(bean, LangChain4jModelMethodInterceptor.class);
    }

    static Object ultimateSingletonTarget(Object bean) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Object candidate = bean;
        while (visited.add(candidate)) {
            Object target = AopProxyUtils.getSingletonTarget(candidate);
            if (target == null) {
                break;
            }
            candidate = target;
        }
        return candidate;
    }

    static Object instrumentSpringAi(Object bean, LangfuseOtel langfuseOtel) {
        if (!SpringAiModelMethodInterceptor.supports(bean) || isManualSpringAiWrapper(bean)) {
            return bean;
        }
        if (hasObservedModelMethod(bean, SpringAiModelMethodInterceptor.MODEL_INTERFACES)) {
            log.debug("Skipping automatic Spring AI model instrumentation because an explicit "
                    + "@ObserveGeneration model method takes precedence for this bean");
            return bean;
        }
        return instrument(bean, new SpringAiModelMethodInterceptor(bean, langfuseOtel), "Spring AI");
    }

    static Object instrumentLangChain4j(Object bean, LangfuseOtel langfuseOtel) {
        if (!LangChain4jModelMethodInterceptor.supports(bean) || isManualLangChain4jWrapper(bean)) {
            return bean;
        }
        if (hasObservedModelMethod(bean, LangChain4jModelMethodInterceptor.MODEL_INTERFACES)) {
            log.debug("Skipping automatic LangChain4j model instrumentation because an explicit "
                    + "@ObserveGeneration model method takes precedence for this bean");
            return bean;
        }
        return instrument(bean, new LangChain4jModelMethodInterceptor(bean, langfuseOtel), "LangChain4j");
    }

    private static Object instrument(Object bean, ModelMethodInterceptor interceptor, String framework) {
        // The scoped target is post-processed independently. Wrapping the externally visible scoped
        // proxy as well would create one observation outside the scope proxy and another inside it.
        if (bean instanceof ScopedObject) {
            return bean;
        }
        if (containsAdvice(bean, interceptor.getClass())) {
            return bean;
        }

        Class<?> beanClass = bean.getClass();
        boolean existingJdkProxy = AopUtils.isJdkDynamicProxy(bean);
        Class<?> userClass = ClassUtils.getUserClass(beanClass);
        if (!existingJdkProxy && !canCreateClassProxy(userClass)) {
            log.warn("Skipping automatic Langfuse instrumentation for final or otherwise non-proxyable {} "
                    + "model type {}. The original bean is preserved; use an interface-based bean or manual "
                    + "instrumentation for this model.", framework, userClass.getName());
            return bean;
        }

        try {
            ProxyFactory proxyFactory = new ProxyFactory();
            proxyFactory.setTarget(bean);
            if (existingJdkProxy) {
                proxyFactory.setInterfaces(ClassUtils.getAllInterfacesForClass(beanClass, beanClass.getClassLoader()));
            } else {
                proxyFactory.setProxyTargetClass(true);
            }
            proxyFactory.addAdvice(interceptor);
            return proxyFactory.getProxy(beanClass.getClassLoader());
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.warn("Could not create a type-preserving Langfuse proxy for {} model {}. "
                    + "The original bean will be used without automatic instrumentation.",
                    framework, userClass.getName(), failure);
            return bean;
        }
    }

    private static boolean containsAdvice(Object bean, Class<?> adviceType) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return containsAdvice(bean, adviceType, visited);
    }

    private static boolean containsAdvice(Object bean, Class<?> adviceType, Set<Object> visited) {
        if (!(bean instanceof Advised) || !visited.add(bean)) {
            return false;
        }
        Advised advised = (Advised) bean;
        for (Advisor advisor : advised.getAdvisors()) {
            if (adviceType.isInstance(advisor.getAdvice())) {
                return true;
            }
        }

        TargetSource targetSource = advised.getTargetSource();
        if (!targetSource.isStatic()) {
            return false;
        }

        Object target = null;
        try {
            target = targetSource.getTarget();
            return target != null && containsAdvice(target, adviceType, visited);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not inspect a static nested AOP target for existing Langfuse advice", failure);
            return false;
        } finally {
            if (target != null) {
                try {
                    targetSource.releaseTarget(target);
                } catch (Throwable failure) {
                    InstrumentationFailureSupport.rethrowIfFatal(failure);
                    log.debug("Could not release a static nested AOP target after advice inspection", failure);
                }
            }
        }
    }

    private static boolean canCreateClassProxy(Class<?> userClass) {
        if (userClass.isInterface() || Modifier.isFinal(userClass.getModifiers())) {
            return false;
        }
        for (Class<?> current = userClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (!Modifier.isStatic(modifiers)
                        && !Modifier.isPrivate(modifiers)
                        && Modifier.isFinal(modifiers)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasObservedModelMethod(Object bean, Class<?>[] modelInterfaces) {
        Class<?> targetClass;
        try {
            targetClass = AopProxyUtils.ultimateTargetClass(bean);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not resolve the ultimate model target while checking @ObserveGeneration", failure);
            targetClass = ClassUtils.getUserClass(bean);
        }

        Set<Class<?>> visitedInterfaces = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> current = targetClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (containsObservedModelMethod(current.getDeclaredMethods(), modelInterfaces)) {
                return true;
            }
            for (Class<?> implementedInterface : current.getInterfaces()) {
                if (containsObservedModelMethod(
                        implementedInterface, modelInterfaces, visitedInterfaces)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsObservedModelMethod(Class<?> candidateInterface,
                                                       Class<?>[] modelInterfaces,
                                                       Set<Class<?>> visitedInterfaces) {
        if (!visitedInterfaces.add(candidateInterface)) {
            return false;
        }
        if (containsObservedModelMethod(candidateInterface.getDeclaredMethods(), modelInterfaces)) {
            return true;
        }
        for (Class<?> parentInterface : candidateInterface.getInterfaces()) {
            if (containsObservedModelMethod(parentInterface, modelInterfaces, visitedInterfaces)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsObservedModelMethod(Method[] methods,
                                                       Class<?>[] modelInterfaces) {
        for (Method method : methods) {
            if (method.isAnnotationPresent(ObserveGeneration.class)
                    && isModelMethod(method, modelInterfaces)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isModelMethod(Method method, Class<?>[] modelInterfaces) {
        for (Class<?> modelInterface : modelInterfaces) {
            try {
                modelInterface.getMethod(method.getName(), method.getParameterTypes());
                return true;
            } catch (NoSuchMethodException ignored) {
                // Try the next supported model role.
            }
        }
        return false;
    }

    private static boolean isManualSpringAiWrapper(Object bean) {
        return bean instanceof TracingSpringAiChatModel
                || bean instanceof TracingSpringAiEmbeddingModel
                || bean instanceof TracingSpringAiImageModel;
    }

    private static boolean isManualLangChain4jWrapper(Object bean) {
        return bean instanceof TracingLangChain4jChatModel
                || bean instanceof TracingStreamingLangChain4jChatModel
                || bean instanceof TracingLangChain4jEmbeddingModel
                || bean instanceof TracingLangChain4jImageModel;
    }

    private abstract static class ModelMethodInterceptor implements MethodInterceptor {

        @Override
        public final Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            Object[] arguments = invocation.getArguments();
            Object routed = invokeModelMethod(method, arguments);
            return routed == Unrouted.INSTANCE ? invocation.proceed() : routed;
        }

        abstract Object invokeModelMethod(Method method, Object[] arguments) throws Throwable;

        final Object invokeIfDeclaredBy(Class<?> modelInterface, Object wrapper,
                                        Method invokedMethod, Object[] arguments) throws Throwable {
            if (wrapper == null) {
                return Unrouted.INSTANCE;
            }
            Method interfaceMethod;
            try {
                interfaceMethod = modelInterface.getMethod(
                        invokedMethod.getName(), invokedMethod.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                return Unrouted.INSTANCE;
            }
            try {
                return interfaceMethod.invoke(wrapper, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static final class SpringAiModelMethodInterceptor extends ModelMethodInterceptor {

        private static final Class<?>[] MODEL_INTERFACES = {
                org.springframework.ai.chat.model.ChatModel.class,
                org.springframework.ai.embedding.EmbeddingModel.class,
                org.springframework.ai.image.ImageModel.class
        };

        private final TracingSpringAiChatModel chat;
        private final TracingSpringAiEmbeddingModel embedding;
        private final TracingSpringAiImageModel image;

        private SpringAiModelMethodInterceptor(Object delegate, LangfuseOtel langfuseOtel) {
            chat = delegate instanceof org.springframework.ai.chat.model.ChatModel
                    ? new TracingSpringAiChatModel((org.springframework.ai.chat.model.ChatModel) delegate, langfuseOtel)
                    : null;
            embedding = delegate instanceof org.springframework.ai.embedding.EmbeddingModel
                    ? new TracingSpringAiEmbeddingModel(
                    (org.springframework.ai.embedding.EmbeddingModel) delegate, langfuseOtel)
                    : null;
            image = delegate instanceof org.springframework.ai.image.ImageModel
                    ? new TracingSpringAiImageModel((org.springframework.ai.image.ImageModel) delegate, langfuseOtel)
                    : null;
        }

        static boolean supports(Object bean) {
            return bean instanceof org.springframework.ai.chat.model.ChatModel
                    || bean instanceof org.springframework.ai.embedding.EmbeddingModel
                    || bean instanceof org.springframework.ai.image.ImageModel;
        }

        @Override
        Object invokeModelMethod(Method method, Object[] arguments) throws Throwable {
            Object result = invokeIfDeclaredBy(MODEL_INTERFACES[0], chat, method, arguments);
            if (result != Unrouted.INSTANCE) return result;
            result = invokeIfDeclaredBy(MODEL_INTERFACES[1], embedding, method, arguments);
            if (result != Unrouted.INSTANCE) return result;
            return invokeIfDeclaredBy(MODEL_INTERFACES[2], image, method, arguments);
        }

    }

    private static final class LangChain4jModelMethodInterceptor extends ModelMethodInterceptor {

        private static final Class<?>[] MODEL_INTERFACES = {
                dev.langchain4j.model.chat.StreamingChatModel.class,
                dev.langchain4j.model.chat.ChatModel.class,
                dev.langchain4j.model.embedding.EmbeddingModel.class,
                dev.langchain4j.model.image.ImageModel.class
        };

        private final TracingStreamingLangChain4jChatModel streaming;
        private final Object chat;
        private final TracingLangChain4jEmbeddingModel embedding;
        private final TracingLangChain4jImageModel image;

        private LangChain4jModelMethodInterceptor(Object delegate, LangfuseOtel langfuseOtel) {
            streaming = delegate instanceof dev.langchain4j.model.chat.StreamingChatModel
                    ? new TracingStreamingLangChain4jChatModel(delegate, langfuseOtel)
                    : null;
            chat = delegate instanceof dev.langchain4j.model.chat.ChatModel
                    ? (streaming != null
                    ? streaming
                    : new TracingLangChain4jChatModel(
                    (dev.langchain4j.model.chat.ChatModel) delegate, langfuseOtel))
                    : null;
            embedding = delegate instanceof dev.langchain4j.model.embedding.EmbeddingModel
                    ? new TracingLangChain4jEmbeddingModel(
                    (dev.langchain4j.model.embedding.EmbeddingModel) delegate, langfuseOtel)
                    : null;
            image = delegate instanceof dev.langchain4j.model.image.ImageModel
                    ? new TracingLangChain4jImageModel(
                    (dev.langchain4j.model.image.ImageModel) delegate, langfuseOtel)
                    : null;
        }

        static boolean supports(Object bean) {
            return bean instanceof dev.langchain4j.model.chat.StreamingChatModel
                    || bean instanceof dev.langchain4j.model.chat.ChatModel
                    || bean instanceof dev.langchain4j.model.embedding.EmbeddingModel
                    || bean instanceof dev.langchain4j.model.image.ImageModel;
        }

        @Override
        Object invokeModelMethod(Method method, Object[] arguments) throws Throwable {
            Object result = invokeIfDeclaredBy(MODEL_INTERFACES[0], streaming, method, arguments);
            if (result != Unrouted.INSTANCE) return result;
            result = invokeIfDeclaredBy(MODEL_INTERFACES[1], chat, method, arguments);
            if (result != Unrouted.INSTANCE) return result;
            result = invokeIfDeclaredBy(MODEL_INTERFACES[2], embedding, method, arguments);
            if (result != Unrouted.INSTANCE) return result;
            return invokeIfDeclaredBy(MODEL_INTERFACES[3], image, method, arguments);
        }

    }

    private enum Unrouted {
        INSTANCE
    }
}

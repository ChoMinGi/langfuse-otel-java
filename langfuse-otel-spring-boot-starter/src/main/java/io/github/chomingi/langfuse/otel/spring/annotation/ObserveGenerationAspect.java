package io.github.chomingi.langfuse.otel.spring.annotation;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

@Aspect
public class ObserveGenerationAspect {

    private static final Logger log = LoggerFactory.getLogger(ObserveGenerationAspect.class);

    private final LangfuseOtel langfuseOtel;

    public ObserveGenerationAspect(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Around("@annotation(io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        ObservationDescriptor descriptor;
        try {
            descriptor = descriptor(joinPoint);
        } catch (Throwable failure) {
            GenerationObservation.rethrowIfFatal(failure);
            log.debug("Langfuse @ObserveGeneration metadata setup failed, proceeding without tracing", failure);
            return joinPoint.proceed();
        }

        Context capturedParent = Context.current();
        LangfuseTraceContext capturedTraceContext = LangfuseContext.current();

        if (ReactorBridge.supportsReturnType(descriptor.method.getReturnType())) {
            return observeReactor(joinPoint, descriptor, capturedParent, capturedTraceContext);
        }

        GenerationObservation observation = startQuietly(
                descriptor, capturedParent, capturedTraceContext);
        if (observation == null) {
            return joinPoint.proceed();
        }

        Object result;
        Scope scope;
        try {
            scope = observation.makeCurrent();
        } catch (Throwable failure) {
            observation.end();
            GenerationObservation.rethrowIfFatal(failure);
            log.debug("Langfuse @ObserveGeneration scope setup failed, proceeding without tracing", failure);
            return joinPoint.proceed();
        }

        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            observation.completeExceptionally(failure);
            throw failure;
        } finally {
            try {
                GenerationObservation.closeScopeQuietly(scope);
            } catch (Throwable closeFailure) {
                // closeScopeQuietly only rethrows fatal failures. Do not leave a raw observation
                // open when such a failure prevents completion handling below from running.
                observation.end();
                throw closeFailure;
            }
        }

        if (result instanceof CompletionStage) {
            attachCompletion((CompletionStage<?>) result, observation);
            return result;
        }

        observation.completeSuccessfully(result);
        return result;
    }

    private Object observeReactor(ProceedingJoinPoint joinPoint,
                                  ObservationDescriptor descriptor,
                                  Context capturedParent,
                                  LangfuseTraceContext capturedTraceContext) throws Throwable {
        Object source;
        try {
            source = joinPoint.proceed();
        } catch (Throwable failure) {
            GenerationObservation observation = startQuietly(
                    descriptor, capturedParent, capturedTraceContext);
            if (observation != null) {
                observation.completeExceptionally(failure);
            }
            throw failure;
        }
        if (source == null) {
            return null;
        }

        try {
            return ReactorBridge.wrap(
                    source, descriptor, langfuseOtel, capturedParent, capturedTraceContext);
        } catch (Throwable failure) {
            GenerationObservation.rethrowIfFatal(failure);
            log.debug("Langfuse reactive @ObserveGeneration wrapping failed, returning the original publisher", failure);
            return source;
        }
    }

    private void attachCompletion(CompletionStage<?> stage, GenerationObservation observation) {
        try {
            stage.whenComplete((value, failure) -> {
                Throwable actualFailure = unwrapCompletionFailure(failure);
                if (actualFailure == null) {
                    observation.completeSuccessfully(value);
                } else if (actualFailure instanceof CancellationException) {
                    observation.cancel();
                } else {
                    observation.completeExceptionally(actualFailure);
                }
            });
        } catch (Throwable failure) {
            observation.end();
            GenerationObservation.rethrowIfFatal(failure);
            log.debug("Could not attach Langfuse completion callback; returning the original stage", failure);
        }
    }

    private GenerationObservation startQuietly(ObservationDescriptor descriptor,
                                               Context parentContext,
                                               LangfuseTraceContext traceContext) {
        try {
            return GenerationObservation.start(
                    langfuseOtel,
                    parentContext,
                    traceContext,
                    descriptor.name,
                    descriptor.operation,
                    descriptor.model,
                    descriptor.system);
        } catch (Throwable failure) {
            GenerationObservation.rethrowIfFatal(failure);
            log.debug("Langfuse @ObserveGeneration setup failed, proceeding without tracing", failure);
            return null;
        }
    }

    private static ObservationDescriptor descriptor(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        ObserveGeneration annotation = method.getAnnotation(ObserveGeneration.class);
        String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
        return new ObservationDescriptor(
                method, name, annotation.operation(), annotation.model(), annotation.system());
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class ObservationDescriptor {
        private final Method method;
        private final String name;
        private final String operation;
        private final String model;
        private final String system;

        private ObservationDescriptor(Method method, String name, String operation,
                                      String model, String system) {
            this.method = method;
            this.name = name;
            this.operation = operation;
            this.model = model;
            this.system = system;
        }
    }

    /** Reflection keeps the always-loaded aspect usable when optional Reactor is absent. */
    private static final class ReactorBridge {
        private static final Method SUPPORTS_RETURN_TYPE;
        private static final Method WRAP;

        static {
            Method supports = null;
            Method wrap = null;
            try {
                ClassLoader classLoader = ObserveGenerationAspect.class.getClassLoader();
                Class.forName("org.reactivestreams.Publisher", false, classLoader);
                Class<?> bridge = Class.forName(
                        "io.github.chomingi.langfuse.otel.spring.annotation.ReactorGenerationObservation",
                        false,
                        classLoader);
                supports = bridge.getDeclaredMethod("supportsReturnType", Class.class);
                wrap = bridge.getDeclaredMethod(
                        "wrap",
                        Object.class,
                        Class.class,
                        LangfuseOtel.class,
                        Context.class,
                        LangfuseTraceContext.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class);
                supports.setAccessible(true);
                wrap.setAccessible(true);
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ignored) {
                // Reactor is optional. CompletionStage and synchronous observation remain available.
            }
            SUPPORTS_RETURN_TYPE = supports;
            WRAP = wrap;
        }

        private static boolean supportsReturnType(Class<?> returnType) {
            if (SUPPORTS_RETURN_TYPE == null) {
                return false;
            }
            try {
                return (Boolean) SUPPORTS_RETURN_TYPE.invoke(null, returnType);
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                return false;
            }
        }

        private static Object wrap(Object source,
                                   ObservationDescriptor descriptor,
                                   LangfuseOtel langfuseOtel,
                                   Context capturedParent,
                                   LangfuseTraceContext capturedTraceContext) throws Throwable {
            if (WRAP == null) {
                return source;
            }
            try {
                return WRAP.invoke(
                        null,
                        source,
                        descriptor.method.getReturnType(),
                        langfuseOtel,
                        capturedParent,
                        capturedTraceContext,
                        descriptor.name,
                        descriptor.operation,
                        descriptor.model,
                        descriptor.system);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}

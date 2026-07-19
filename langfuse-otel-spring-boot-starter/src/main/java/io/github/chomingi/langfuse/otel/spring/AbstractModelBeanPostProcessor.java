package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates normal and early model proxy creation.
 *
 * <p>Spring invokes every registered post-processor when it exposes an early singleton reference.
 * Because each model family retains its role-specific post-processor bean names for compatibility,
 * all of those processors must remember that the shared early reference already contains the
 * Langfuse advice. Returning the raw bean from the corresponding after-initialization passes lets
 * the bean factory promote that one early proxy to the final singleton.</p>
 */
abstract class AbstractModelBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor {

    private final ObjectProvider<LangfuseOtel> langfuseOtelProvider;
    private final ModelFramework framework;
    private final Map<String, Object> earlyProxyTargets = new ConcurrentHashMap<>();

    AbstractModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider,
                                   ModelFramework framework) {
        this.langfuseOtelProvider = langfuseOtelProvider;
        this.framework = framework;
    }

    @Override
    public final Object getEarlyBeanReference(Object bean, String beanName) throws BeansException {
        Object reference = instrumentIfAvailable(bean);
        if (framework.isInstrumented(reference)) {
            earlyProxyTargets.put(
                    beanName, ModelBeanPostProcessorSupport.ultimateSingletonTarget(bean));
        }
        return reference;
    }

    @Override
    public final Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        Object earlyProxyTarget = earlyProxyTargets.remove(beanName);
        if (earlyProxyTarget != null
                && earlyProxyTarget == ModelBeanPostProcessorSupport.ultimateSingletonTarget(bean)) {
            return bean;
        }
        return instrumentIfAvailable(bean);
    }

    private Object instrumentIfAvailable(Object bean) {
        if (!framework.supports(bean)) {
            return bean;
        }
        LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
        return langfuseOtel != null ? framework.instrument(bean, langfuseOtel) : bean;
    }

    enum ModelFramework {
        SPRING_AI {
            @Override
            boolean supports(Object bean) {
                return ModelBeanPostProcessorSupport.isSpringAiModel(bean);
            }

            @Override
            Object instrument(Object bean, LangfuseOtel langfuseOtel) {
                return ModelBeanPostProcessorSupport.instrumentSpringAi(bean, langfuseOtel);
            }

            @Override
            boolean isInstrumented(Object bean) {
                return ModelBeanPostProcessorSupport.hasSpringAiInstrumentation(bean);
            }
        },
        LANGCHAIN4J {
            @Override
            boolean supports(Object bean) {
                return ModelBeanPostProcessorSupport.isLangChain4jModel(bean);
            }

            @Override
            Object instrument(Object bean, LangfuseOtel langfuseOtel) {
                return ModelBeanPostProcessorSupport.instrumentLangChain4j(bean, langfuseOtel);
            }

            @Override
            boolean isInstrumented(Object bean) {
                return ModelBeanPostProcessorSupport.hasLangChain4jInstrumentation(bean);
            }
        };

        abstract boolean supports(Object bean);

        abstract Object instrument(Object bean, LangfuseOtel langfuseOtel);

        abstract boolean isInstrumented(Object bean);
    }
}

package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

class SpringAiEmbeddingModelBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LangfuseOtel> langfuseOtelProvider;

    SpringAiEmbeddingModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        this.langfuseOtelProvider = langfuseOtelProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof TracingSpringAiEmbeddingModel) {
            return bean;
        }
        if (bean instanceof EmbeddingModel) {
            LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
            return langfuseOtel != null ? new TracingSpringAiEmbeddingModel((EmbeddingModel) bean, langfuseOtel) : bean;
        }
        return bean;
    }
}

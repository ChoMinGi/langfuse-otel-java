package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

class LangChain4jEmbeddingModelBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LangfuseOtel> langfuseOtelProvider;

    LangChain4jEmbeddingModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        this.langfuseOtelProvider = langfuseOtelProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof TracingLangChain4jEmbeddingModel) {
            return bean;
        }
        if (bean instanceof dev.langchain4j.model.embedding.EmbeddingModel) {
            LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
            return langfuseOtel != null
                    ? new TracingLangChain4jEmbeddingModel((dev.langchain4j.model.embedding.EmbeddingModel) bean, langfuseOtel)
                    : bean;
        }
        return bean;
    }
}

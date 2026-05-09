package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

class LangChain4jImageModelBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LangfuseOtel> langfuseOtelProvider;

    LangChain4jImageModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        this.langfuseOtelProvider = langfuseOtelProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof TracingLangChain4jImageModel) {
            return bean;
        }
        if (bean instanceof dev.langchain4j.model.image.ImageModel) {
            LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
            return langfuseOtel != null
                    ? new TracingLangChain4jImageModel((dev.langchain4j.model.image.ImageModel) bean, langfuseOtel)
                    : bean;
        }
        return bean;
    }
}

package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.ai.image.ImageModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

class SpringAiImageModelBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LangfuseOtel> langfuseOtelProvider;

    SpringAiImageModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        this.langfuseOtelProvider = langfuseOtelProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof TracingSpringAiImageModel) {
            return bean;
        }
        if (bean instanceof ImageModel) {
            LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
            return langfuseOtel != null ? new TracingSpringAiImageModel((ImageModel) bean, langfuseOtel) : bean;
        }
        return bean;
    }
}

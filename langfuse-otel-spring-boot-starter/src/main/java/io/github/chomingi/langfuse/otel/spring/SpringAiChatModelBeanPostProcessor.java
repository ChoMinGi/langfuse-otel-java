package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

class SpringAiChatModelBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LangfuseOtel> langfuseOtelProvider;

    SpringAiChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        this.langfuseOtelProvider = langfuseOtelProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof TracingSpringAiChatModel) {
            return bean;
        }
        if (bean instanceof ChatModel) {
            LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
            return langfuseOtel != null ? new TracingSpringAiChatModel((ChatModel) bean, langfuseOtel) : bean;
        }
        return bean;
    }
}

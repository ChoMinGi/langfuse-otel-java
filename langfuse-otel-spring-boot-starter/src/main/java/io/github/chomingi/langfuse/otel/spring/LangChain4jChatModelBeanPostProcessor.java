package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

class LangChain4jChatModelBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LangfuseOtel> langfuseOtelProvider;

    LangChain4jChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        this.langfuseOtelProvider = langfuseOtelProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof TracingStreamingLangChain4jChatModel) {
            return bean;
        }
        if (bean instanceof TracingLangChain4jChatModel) {
            return bean;
        }
        if (bean instanceof dev.langchain4j.model.chat.StreamingChatModel) {
            LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
            return langfuseOtel != null ? new TracingStreamingLangChain4jChatModel(bean, langfuseOtel) : bean;
        }
        if (bean instanceof dev.langchain4j.model.chat.ChatModel) {
            LangfuseOtel langfuseOtel = langfuseOtelProvider.getIfAvailable();
            return langfuseOtel != null ? new TracingLangChain4jChatModel((dev.langchain4j.model.chat.ChatModel) bean, langfuseOtel) : bean;
        }
        return bean;
    }
}

package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

@AutoConfiguration(after = LangfuseOtelAutoConfiguration.class)
@ConditionalOnClass(name = "dev.langchain4j.model.chat.ChatModel")
@ConditionalOnBean(LangfuseOtel.class)
public class LangChain4jAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static LangChain4jChatModelBeanPostProcessor langChain4jChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new LangChain4jChatModelBeanPostProcessor(langfuseOtelProvider);
    }
}

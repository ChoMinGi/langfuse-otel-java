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
@ConditionalOnClass(name = "org.springframework.ai.chat.model.ChatModel")
@ConditionalOnBean(LangfuseOtel.class)
public class SpringAiAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SpringAiChatModelBeanPostProcessor springAiChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new SpringAiChatModelBeanPostProcessor(langfuseOtelProvider);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SpringAiEmbeddingModelBeanPostProcessor springAiEmbeddingModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new SpringAiEmbeddingModelBeanPostProcessor(langfuseOtelProvider);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SpringAiImageModelBeanPostProcessor springAiImageModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new SpringAiImageModelBeanPostProcessor(langfuseOtelProvider);
    }
}

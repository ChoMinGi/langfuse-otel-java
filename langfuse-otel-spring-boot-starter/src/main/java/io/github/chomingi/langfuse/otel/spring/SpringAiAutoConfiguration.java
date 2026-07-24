package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/**
 * Adds Langfuse tracing decorators to supported Spring AI model beans.
 */
@AutoConfiguration(after = LangfuseOtelCoreAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.ai.chat.model.ChatModel")
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(LangfuseOtel.class)
public class SpringAiAutoConfiguration {

    /**
     * Creates infrastructure that decorates Spring AI chat models.
     *
     * @param langfuseOtelProvider provider for the tracing integration
     * @return the chat model post-processor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SpringAiChatModelBeanPostProcessor springAiChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new SpringAiChatModelBeanPostProcessor(langfuseOtelProvider);
    }

    /**
     * Creates infrastructure that decorates Spring AI embedding models.
     *
     * @param langfuseOtelProvider provider for the tracing integration
     * @return the embedding model post-processor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SpringAiEmbeddingModelBeanPostProcessor springAiEmbeddingModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new SpringAiEmbeddingModelBeanPostProcessor(langfuseOtelProvider);
    }

    /**
     * Creates infrastructure that decorates Spring AI image models.
     *
     * @param langfuseOtelProvider provider for the tracing integration
     * @return the image model post-processor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static SpringAiImageModelBeanPostProcessor springAiImageModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new SpringAiImageModelBeanPostProcessor(langfuseOtelProvider);
    }
}

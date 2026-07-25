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
 * Adds Langfuse tracing decorators to supported LangChain4j model beans.
 */
@AutoConfiguration(after = LangfuseOtelCoreAutoConfiguration.class)
@ConditionalOnClass(name = "dev.langchain4j.model.chat.ChatModel")
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(LangfuseOtel.class)
public class LangChain4jAutoConfiguration {

    /**
     * Creates infrastructure that decorates LangChain4j chat models.
     *
     * @param langfuseOtelProvider provider for the tracing integration
     * @return the chat model post-processor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static LangChain4jChatModelBeanPostProcessor langChain4jChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new LangChain4jChatModelBeanPostProcessor(langfuseOtelProvider);
    }

    /**
     * Creates infrastructure that decorates LangChain4j embedding models.
     *
     * @param langfuseOtelProvider provider for the tracing integration
     * @return the embedding model post-processor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static LangChain4jEmbeddingModelBeanPostProcessor langChain4jEmbeddingModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new LangChain4jEmbeddingModelBeanPostProcessor(langfuseOtelProvider);
    }

    /**
     * Creates infrastructure that decorates LangChain4j image models.
     *
     * @param langfuseOtelProvider provider for the tracing integration
     * @return the image model post-processor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static LangChain4jImageModelBeanPostProcessor langChain4jImageModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        return new LangChain4jImageModelBeanPostProcessor(langfuseOtelProvider);
    }
}

package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = LangfuseOtelAutoConfiguration.class)
@ConditionalOnClass(ChatModel.class)
@ConditionalOnBean(LangfuseOtel.class)
public class SpringAiAutoConfiguration {

    @Bean
    public SpringAiInstrumentationAspect springAiInstrumentationAspect(LangfuseOtel langfuseOtel) {
        return new SpringAiInstrumentationAspect(langfuseOtel);
    }
}

package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = LangfuseOtelAutoConfiguration.class)
@ConditionalOnClass(name = "dev.langchain4j.model.chat.ChatModel")
@ConditionalOnBean(LangfuseOtel.class)
public class LangChain4jAutoConfiguration {

    @Bean
    public LangChain4jInstrumentationAspect langChain4jInstrumentationAspect(LangfuseOtel langfuseOtel) {
        return new LangChain4jInstrumentationAspect(langfuseOtel);
    }
}

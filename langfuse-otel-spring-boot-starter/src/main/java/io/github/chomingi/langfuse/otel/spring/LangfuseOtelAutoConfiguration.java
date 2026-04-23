package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(LangfuseOtelProperties.class)
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LangfuseOtelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LangfuseOtel langfuseOtel(LangfuseOtelProperties properties) {
        LangfuseOtel.Builder builder = LangfuseOtel.builder()
                .publicKey(properties.getPublicKey())
                .secretKey(properties.getSecretKey())
                .host(properties.getHost())
                .serviceName(properties.getServiceName());

        if (properties.getEnvironment() != null) {
            builder.environment(properties.getEnvironment());
        }
        if (properties.getRelease() != null) {
            builder.release(properties.getRelease());
        }

        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    public LangfuseContextFilter langfuseContextFilter(LangfuseOtelProperties properties) {
        return new LangfuseContextFilter(properties);
    }
}

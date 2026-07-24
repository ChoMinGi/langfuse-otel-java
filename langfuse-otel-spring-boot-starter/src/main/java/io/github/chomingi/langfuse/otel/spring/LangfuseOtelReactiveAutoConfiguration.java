package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Adds request-derived Langfuse context to reactive web applications. */
@AutoConfiguration(after = LangfuseOtelCoreAutoConfiguration.class)
@EnableConfigurationProperties(LangfuseOtelProperties.class)
@ConditionalOnClass(name = {
        "org.springframework.web.server.WebFilter",
        "reactor.core.publisher.Mono"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(LangfuseOtel.class)
public class LangfuseOtelReactiveAutoConfiguration {

    /**
     * Creates the WebFlux filter that propagates request-derived Langfuse context.
     *
     * @param properties starter configuration
     * @return the reactive context filter
     */
    @Bean
    @ConditionalOnMissingBean
    public LangfuseReactiveContextFilter langfuseReactiveContextFilter(
            LangfuseOtelProperties properties) {
        return new LangfuseReactiveContextFilter(properties);
    }
}

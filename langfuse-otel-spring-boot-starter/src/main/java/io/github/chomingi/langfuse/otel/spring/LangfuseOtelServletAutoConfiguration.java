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

/** Adds request-derived Langfuse context to servlet applications. */
@AutoConfiguration(after = LangfuseOtelCoreAutoConfiguration.class)
@EnableConfigurationProperties(LangfuseOtelProperties.class)
@ConditionalOnClass(name = {
        "jakarta.servlet.Filter",
        "org.springframework.web.filter.OncePerRequestFilter"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(LangfuseOtel.class)
public class LangfuseOtelServletAutoConfiguration {

    /**
     * Creates the servlet filter that propagates request-derived Langfuse context.
     *
     * @param properties starter configuration
     * @return the servlet context filter
     */
    @Bean
    @ConditionalOnMissingBean
    public LangfuseContextFilter langfuseContextFilter(LangfuseOtelProperties properties) {
        return new LangfuseContextFilter(properties);
    }
}

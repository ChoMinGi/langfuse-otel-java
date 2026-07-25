package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.ContentRedactor;
import io.github.chomingi.langfuse.otel.ExceptionRedactor;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGenerationAspect;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Compatibility facade for the public factory methods exposed in the 0.1 release line.
 *
 * @deprecated Spring Boot applications should rely on auto-configuration. The runtime
 * auto-configuration is provided by {@link LangfuseOtelCoreAutoConfiguration}.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
public class LangfuseOtelAutoConfiguration {

    /**
     * Creates a standalone integration from application properties.
     *
     * @param properties standalone configuration properties
     * @return the configured tracing integration
     * @deprecated Prefer Spring auto-configuration or {@link LangfuseOtel#builder()}.
     */
    @Deprecated(since = "0.2.0", forRemoval = false)
    public LangfuseOtel langfuseOtel(LangfuseOtelProperties properties) {
        return new LangfuseOtelCoreAutoConfiguration().langfuseOtel(properties);
    }

    /**
     * Creates an integration from application properties and available extension beans.
     *
     * @param properties starter configuration
     * @param openTelemetryProvider application OpenTelemetry beans
     * @param contentRedactorProvider content redactor beans
     * @param exceptionRedactorProvider exception redactor beans
     * @return the configured tracing integration
     */
    public LangfuseOtel langfuseOtel(LangfuseOtelProperties properties,
                                     ObjectProvider<OpenTelemetry> openTelemetryProvider,
                                     ObjectProvider<ContentRedactor> contentRedactorProvider,
                                     ObjectProvider<ExceptionRedactor> exceptionRedactorProvider) {
        return new LangfuseOtelCoreAutoConfiguration().langfuseOtel(
                properties,
                openTelemetryProvider,
                contentRedactorProvider,
                exceptionRedactorProvider);
    }

    /**
     * Creates the aspect that traces methods annotated with {@code @ObserveGeneration}.
     *
     * @param langfuseOtel tracing integration
     * @return the generation observation aspect
     */
    public ObserveGenerationAspect observeGenerationAspect(LangfuseOtel langfuseOtel) {
        return new ObserveGenerationAspect(langfuseOtel);
    }

    /**
     * Creates the servlet filter that propagates request-derived Langfuse context.
     *
     * @param properties starter configuration
     * @return the servlet context filter
     */
    public LangfuseContextFilter langfuseContextFilter(LangfuseOtelProperties properties) {
        return new LangfuseContextFilter(properties);
    }

    /**
     * Creates the WebFlux filter that propagates request-derived Langfuse context.
     *
     * @param properties starter configuration
     * @return the reactive context filter
     */
    public LangfuseReactiveContextFilter langfuseReactiveContextFilter(
            LangfuseOtelProperties properties) {
        return new LangfuseReactiveContextFilter(properties);
    }
}

package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.ContentCapturePolicy;
import io.github.chomingi.langfuse.otel.ContentRedactor;
import io.github.chomingi.langfuse.otel.ExceptionCapturePolicy;
import io.github.chomingi.langfuse.otel.ExceptionRedactor;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGenerationAspect;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static io.github.chomingi.langfuse.otel.spring.LangfuseOtelProperties.OpenTelemetryMode.AUTO;
import static io.github.chomingi.langfuse.otel.spring.LangfuseOtelProperties.OpenTelemetryMode.EXTERNAL;

@AutoConfiguration
@EnableConfigurationProperties(LangfuseOtelProperties.class)
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LangfuseOtelAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LangfuseOtelAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public LangfuseOtel langfuseOtel(LangfuseOtelProperties properties,
                                     ObjectProvider<OpenTelemetry> openTelemetryProvider,
                                     ObjectProvider<ContentRedactor> contentRedactorProvider,
                                     ObjectProvider<ExceptionRedactor> exceptionRedactorProvider) {
        List<ContentRedactor> contentRedactors = contentRedactorProvider.orderedStream()
                .collect(Collectors.toList());
        List<ExceptionRedactor> exceptionRedactors = exceptionRedactorProvider.orderedStream()
                .collect(Collectors.toList());
        List<OpenTelemetry> externalOpenTelemetryBeans = openTelemetryProvider.orderedStream()
                .collect(Collectors.toList());
        OpenTelemetry externalOpenTelemetry = openTelemetryProvider.getIfUnique();

        return createLangfuseOtel(properties, contentRedactors, exceptionRedactors,
                externalOpenTelemetryBeans, externalOpenTelemetry);
    }

    /**
     * Compatibility bridge for callers that directly invoked the 0.1.x auto-configuration factory.
     * This direct call does not discover Spring beans; {@code AUTO} mode therefore uses the
     * standalone path.
     *
     * @param properties standalone configuration properties
     * @return the configured Langfuse client
     * @deprecated Prefer Spring auto-configuration or {@link LangfuseOtel#builder()}.
     */
    @Deprecated(since = "0.2.0", forRemoval = false)
    public LangfuseOtel langfuseOtel(LangfuseOtelProperties properties) {
        return createLangfuseOtel(properties, List.of(), List.of(), List.of(), null);
    }

    private LangfuseOtel createLangfuseOtel(LangfuseOtelProperties properties,
                                            List<ContentRedactor> contentRedactors,
                                            List<ExceptionRedactor> exceptionRedactors,
                                            List<OpenTelemetry> externalOpenTelemetryBeans,
                                            OpenTelemetry externalOpenTelemetry) {
        ContentCapturePolicy.Builder capturePolicyBuilder = ContentCapturePolicy.builder()
                .captureInput(properties.getContent().isCaptureInput())
                .captureOutput(properties.getContent().isCaptureOutput())
                .maxLength(properties.getContent().getMaxLength());
        if (contentRedactors.size() == 1) {
            capturePolicyBuilder.redactor(contentRedactors.get(0));
        } else if (contentRedactors.size() > 1) {
            log.warn("Multiple ContentRedactor beans found. Automatic content will be dropped until exactly one is configured.");
            capturePolicyBuilder.redactor((type, content) -> null);
        }

        ExceptionCapturePolicy.Builder exceptionPolicyBuilder = ExceptionCapturePolicy.builder()
                .captureMessage(properties.getException().isCaptureMessage())
                .captureStackTrace(properties.getException().isCaptureStackTrace())
                .maxLength(properties.getException().getMaxLength());
        if (exceptionRedactors.size() == 1) {
            exceptionPolicyBuilder.redactor(exceptionRedactors.get(0));
        } else if (exceptionRedactors.size() > 1) {
            log.warn("Multiple ExceptionRedactor beans found. Exception details will be dropped until exactly one is configured.");
            exceptionPolicyBuilder.redactor((type, content) -> null);
        }

        LangfuseOtelProperties.OpenTelemetryMode otelMode = properties.getOtelMode();
        if (otelMode == null) {
            throw new IllegalStateException("langfuse.otel-mode must not be null");
        }

        boolean useExternal = otelMode == EXTERNAL
                || (otelMode == AUTO && externalOpenTelemetry != null);
        if ((otelMode == EXTERNAL && externalOpenTelemetry == null)
                || (otelMode == AUTO && !externalOpenTelemetryBeans.isEmpty()
                && externalOpenTelemetry == null)) {
            throw new IllegalStateException("langfuse.otel-mode=" + otelMode.name().toLowerCase(Locale.ROOT)
                    + " requires exactly one unambiguous OpenTelemetry bean, but found "
                    + externalOpenTelemetryBeans.size()
                    + ". Select a primary bean or set langfuse.otel-mode=standalone.");
        }

        LangfuseOtel.Builder builder;
        if (useExternal) {
            log.warn("Using the application OpenTelemetry bean. The starter will not create a Langfuse exporter, "
                    + "and standalone public-key, secret-key, host, and service settings are not applied. "
                    + "Ensure the application pipeline exports OTLP traces to Langfuse, or set "
                    + "langfuse.otel-mode=standalone.");
            builder = LangfuseOtel.externalBuilder(externalOpenTelemetry);
        } else {
            builder = LangfuseOtel.builder()
                    .publicKey(properties.getPublicKey())
                    .secretKey(properties.getSecretKey())
                    .host(properties.getHost())
                    .serviceName(properties.getServiceName())
                    .allowInsecureHttpForDevelopment(properties.isAllowInsecureHttpForDevelopment());

            if (properties.getEnvironment() != null) {
                builder.environment(properties.getEnvironment());
            }
            if (properties.getRelease() != null) {
                builder.release(properties.getRelease());
            }
        }

        return builder
                .contentCapturePolicy(capturePolicyBuilder.build())
                .exceptionCapturePolicy(exceptionPolicyBuilder.build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(LangfuseOtel.class)
    public ObserveGenerationAspect observeGenerationAspect(LangfuseOtel langfuseOtel) {
        return new ObserveGenerationAspect(langfuseOtel);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    public LangfuseContextFilter langfuseContextFilter(LangfuseOtelProperties properties) {
        return new LangfuseContextFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
    @ConditionalOnMissingClass("jakarta.servlet.Filter")
    public LangfuseReactiveContextFilter langfuseReactiveContextFilter(LangfuseOtelProperties properties) {
        return new LangfuseReactiveContextFilter(properties);
    }
}

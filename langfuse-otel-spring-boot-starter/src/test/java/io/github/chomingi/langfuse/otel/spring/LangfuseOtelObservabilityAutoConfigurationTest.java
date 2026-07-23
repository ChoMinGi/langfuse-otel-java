package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LangfuseOtelAutoConfiguration.class,
                    LangfuseOtelObservabilityAutoConfiguration.class));

    private final ApplicationContextRunner standaloneRunner = baseRunner.withPropertyValues(
            "langfuse.public-key=pk-test",
            "langfuse.secret-key=sk-test");

    @Test
    void standaloneRegistersUpHealthAndManagedMeterBinder() {
        standaloneRunner.run(context -> {
            assertThat(context).hasSingleBean(LangfuseOtel.class);
            assertThat(context).hasBean("langfuseHealthIndicator");
            assertThat(context).hasBean("langfuseOtelMeterBinder");

            Health health = context.getBean("langfuseHealthIndicator", HealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("ownership", "owned")
                    .containsEntry("operationalSignals", "managed")
                    .containsEntry("exportState", "not_attempted")
                    .doesNotContainKeys("host", "endpoint", "publicKey", "secretKey");
        });
    }

    @Test
    void failSafeNoopIsOutOfServiceWithBoundedReason() {
        baseRunner.run(context -> {
            Health health = context.getBean("langfuseHealthIndicator", HealthIndicator.class).health();

            assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(health.getDetails())
                    .containsEntry("ownership", "none")
                    .containsEntry("noopFallback", true)
                    .containsEntry("noopReason", "missing_credentials")
                    .containsEntry("operationalSignals", "not_managed");

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            try {
                context.getBean("langfuseOtelMeterBinder", MeterBinder.class).bindTo(registry);
                assertThat(registry.get("langfuse.otel.noop.fallback")
                        .tag("ownership", "none")
                        .tag("fallback_reason", "missing_credentials")
                        .gauge().value()).isEqualTo(1.0);
                assertThat(registry.find("langfuse.otel.export.failed.spans").meter()).isNull();
            } finally {
                registry.close();
            }
        });
    }

    @Test
    void externalPipelineIsUnknownAndDoesNotPublishTransportMeters() {
        baseRunner.withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .run(context -> {
                    Health health = context.getBean(
                            "langfuseHealthIndicator", HealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
                    assertThat(health.getDetails())
                            .containsEntry("ownership", "external")
                            .containsEntry("operationalSignals", "not_managed");

                    SimpleMeterRegistry registry = new SimpleMeterRegistry();
                    try {
                        context.getBean("langfuseOtelMeterBinder", MeterBinder.class).bindTo(registry);
                        assertThat(registry.get("langfuse.otel.noop.fallback")
                                .tag("ownership", "external")
                                .tag("fallback_reason", "none")
                                .gauge().value()).isZero();
                        assertThat(registry.find("langfuse.otel.export.failed.spans").meter()).isNull();
                        assertThat(registry.find("langfuse.otel.queue.dropped.spans").meter()).isNull();
                        assertThat(registry.find("langfuse.otel.flush.failures").meter()).isNull();
                    } finally {
                        registry.close();
                    }
                });
    }

    @Test
    void optionalClasspathConditionsLeaveCoreIntegrationUsable() {
        standaloneRunner
                .withClassLoader(new FilteredClassLoader(
                        HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    assertThat(context).doesNotHaveBean("langfuseHealthIndicator");
                    assertThat(context).hasBean("langfuseOtelMeterBinder");
                });

        standaloneRunner
                .withClassLoader(new FilteredClassLoader(
                        MeterRegistry.class, MeterBinder.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    assertThat(context).hasBean("langfuseHealthIndicator");
                    assertThat(context).doesNotHaveBean("langfuseOtelMeterBinder");
                });

        standaloneRunner
                .withClassLoader(new FilteredClassLoader(
                        HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class,
                        MeterRegistry.class, MeterBinder.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    assertThat(context).doesNotHaveBean("langfuseHealthIndicator");
                    assertThat(context).doesNotHaveBean("langfuseOtelMeterBinder");
                });
    }

    @Test
    void standardHealthDisablePropertyIsHonored() {
        standaloneRunner.withPropertyValues("management.health.langfuse.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("langfuseHealthIndicator");
                    assertThat(context).hasBean("langfuseOtelMeterBinder");
                });
    }

    @Test
    void globalHealthDisablePropertyIsHonored() {
        standaloneRunner.withPropertyValues("management.health.defaults.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean("langfuseHealthIndicator"));
    }

    @Test
    void bootMetricsAutoConfigurationBindsMetersAutomatically() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LangfuseOtelAutoConfiguration.class,
                        LangfuseOtelObservabilityAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class))
                .withPropertyValues(
                        "langfuse.public-key=pk-test",
                        "langfuse.secret-key=sk-test")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("langfuse.otel.noop.fallback").gauge()).isNotNull();
                    assertThat(registry.find("langfuse.otel.export.failed.spans")
                            .functionCounter()).isNotNull();
                });
    }

    @Test
    void standardMetricsDisablePropertyIsHonored() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LangfuseOtelAutoConfiguration.class,
                        LangfuseOtelObservabilityAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class))
                .withPropertyValues(
                        "langfuse.public-key=pk-test",
                        "langfuse.secret-key=sk-test",
                        "management.metrics.enable.langfuse=false")
                .run(context -> assertThat(context.getBean(MeterRegistry.class)
                        .find("langfuse.otel.noop.fallback").meter()).isNull());
    }

    @Test
    void disablingLangfuseRemovesBothOperationalBeans() {
        standaloneRunner.withPropertyValues("langfuse.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LangfuseOtel.class);
                    assertThat(context).doesNotHaveBean("langfuseHealthIndicator");
                    assertThat(context).doesNotHaveBean("langfuseOtelMeterBinder");
                });
    }

    @Test
    void namedUserBeansTakePrecedence() {
        HealthIndicator userHealth = () -> Health.up().withDetail("source", "user").build();
        MeterBinder userBinder = registry -> {};

        standaloneRunner
                .withBean("langfuseHealthIndicator", HealthIndicator.class, () -> userHealth)
                .withBean("langfuseOtelMeterBinder", MeterBinder.class, () -> userBinder)
                .run(context -> {
                    assertThat(context.getBean("langfuseHealthIndicator")).isSameAs(userHealth);
                    assertThat(context.getBean("langfuseOtelMeterBinder")).isSameAs(userBinder);
                });
    }
}

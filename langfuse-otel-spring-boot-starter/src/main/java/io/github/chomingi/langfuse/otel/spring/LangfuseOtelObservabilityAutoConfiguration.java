package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adds optional Actuator and Micrometer signals for the Langfuse OTel runtime. */
@AutoConfiguration(after = LangfuseOtelCoreAutoConfiguration.class)
public class LangfuseOtelObservabilityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class})
    @ConditionalOnBean(LangfuseOtel.class)
    static class HealthConfiguration {

        @Bean
        @ConditionalOnEnabledHealthIndicator("langfuse")
        @ConditionalOnMissingBean(name = "langfuseHealthIndicator")
        HealthIndicator langfuseHealthIndicator(LangfuseOtel langfuseOtel) {
            return new LangfuseOtelHealthIndicator(langfuseOtel);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterBinder.class)
    @ConditionalOnBean(LangfuseOtel.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "langfuseOtelMeterBinder")
        MeterBinder langfuseOtelMeterBinder(LangfuseOtel langfuseOtel) {
            return new LangfuseOtelMeterBinder(langfuseOtel);
        }
    }
}

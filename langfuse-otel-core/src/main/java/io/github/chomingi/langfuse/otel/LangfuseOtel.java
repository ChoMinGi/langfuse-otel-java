package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Entry point for Langfuse tracing via OpenTelemetry. Use {@link #builder()} to configure and create an instance.
 *
 * <pre>{@code
 * try (LangfuseOtel langfuse = LangfuseOtel.builder()
 *         .publicKey("pk-lf-...")
 *         .secretKey("sk-lf-...")
 *         .build()) {
 *     langfuse.trace("my-flow", trace -> {
 *         trace.generation("llm-call", gen -> {
 *             gen.model("gpt-4o").input(prompt).output(response);
 *         });
 *     });
 * }
 * }</pre>
 */
public class LangfuseOtel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LangfuseOtel.class);

    private static final String DEFAULT_HOST = "https://cloud.langfuse.com";
    private static final String OTEL_PATH = "/api/public/otel/v1/traces";
    private static final String TRACER_NAME = "langfuse-otel-java";
    private static final String LIB_VERSION = resolveLibraryVersion();

    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final Object langfuseClient;
    private final boolean noop;

    LangfuseOtel(SdkTracerProvider tracerProvider, OpenTelemetry openTelemetry,
                 Object langfuseClient, boolean noop) {
        this.tracerProvider = tracerProvider;
        this.tracer = openTelemetry.getTracer(TRACER_NAME, LIB_VERSION);
        this.langfuseClient = langfuseClient;
        this.noop = noop;
    }

    private static LangfuseOtel createNoop() {
        return new LangfuseOtel(null, OpenTelemetry.noop(), null, true);
    }

    public Object getLangfuseClient() {
        return langfuseClient;
    }

    /** Creates a new builder for configuring the Langfuse OTel integration. */
    public static Builder builder() {
        return new Builder();
    }

    private static String resolveLibraryVersion() {
        String version = LangfuseOtel.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    public Tracer getTracer() {
        return tracer;
    }

    public boolean isNoop() {
        return noop;
    }

    /** Creates a new trace. Caller must close the returned trace (try-with-resources or {@code end()}). */
    public LangfuseTrace trace(String name) {
        return new LangfuseTrace(tracer, name);
    }

    /** Creates a trace, executes the action, and automatically closes it. Exceptions propagate after recording. */
    public void trace(String name, Consumer<LangfuseTrace> action) {
        try (LangfuseTrace trace = new LangfuseTrace(tracer, name)) {
            try {
                action.accept(trace);
            } catch (Exception e) {
                trace.recordException(e);
                throw e;
            }
        }
    }

    public void flush() {
        if (tracerProvider != null) {
            tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        if (tracerProvider != null) {
            tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    public static class Builder {

        private String publicKey;
        private String secretKey;
        private String host = DEFAULT_HOST;
        private String serviceName = "langfuse-app";
        private String environment;
        private String release;
        private Object langfuseClient;
        private boolean failSafe = true;

        private Builder() {}

        public Builder publicKey(String publicKey) { this.publicKey = publicKey; return this; }
        public Builder secretKey(String secretKey) { this.secretKey = secretKey; return this; }
        public Builder host(String host) { this.host = host; return this; }
        public Builder serviceName(String serviceName) { this.serviceName = serviceName; return this; }
        public Builder environment(String environment) { this.environment = environment; return this; }
        public Builder release(String release) { this.release = release; return this; }
        public Builder langfuseClient(Object langfuseClient) { this.langfuseClient = langfuseClient; return this; }
        public Builder failSafe(boolean failSafe) { this.failSafe = failSafe; return this; }

        public LangfuseOtel build() {
            // failSafe=true: never crash the host app on misconfiguration (DESIGN.md #3)
            if (publicKey == null || publicKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
                if (failSafe) {
                    log.warn("Langfuse API keys not configured. Running in no-op mode — traces will not be sent.");
                    return LangfuseOtel.createNoop();
                }
                throw new IllegalArgumentException("publicKey and secretKey are required");
            }

            try {
                String authHeader = "Basic " + Base64.getEncoder()
                        .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));

                String endpoint = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
                endpoint += OTEL_PATH;

                OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint)
                        .addHeader("Authorization", authHeader)
                        .addHeader("x-langfuse-ingestion-version", "4")
                        .build();

                ResourceBuilder resourceBuilder = Resource.builder()
                        .put("service.name", serviceName);
                if (environment != null && !environment.isEmpty()) {
                    resourceBuilder.put(LangfuseAttributes.ENVIRONMENT, environment);
                }
                if (release != null && !release.isEmpty()) {
                    resourceBuilder.put(LangfuseAttributes.RELEASE, release);
                }
                Resource resource = Resource.getDefault().merge(resourceBuilder.build());

                SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(new LangfuseContextSpanProcessor())
                        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                        .build();

                OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerProvider)
                        .build();

                return new LangfuseOtel(tracerProvider, otel, langfuseClient, false);
            } catch (Exception e) {
                if (failSafe) {
                    log.warn("Failed to initialize Langfuse OTel. Running in no-op mode.", e);
                    return LangfuseOtel.createNoop();
                }
                throw e;
            }
        }
    }
}

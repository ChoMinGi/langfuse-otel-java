package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
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

    /** Describes who is responsible for the OpenTelemetry lifecycle used by this instance. */
    public enum OpenTelemetryOwnership {
        /** LangfuseOtel created the SDK and will flush and shut it down. */
        OWNED,
        /** The application supplied OpenTelemetry and retains its complete lifecycle. */
        EXTERNAL,
        /** No active SDK is associated with this no-op instance. */
        NONE
    }

    private final SdkTracerProvider ownedTracerProvider;
    private final Tracer tracer;
    private final Object langfuseClient;
    private final boolean noop;
    private final OpenTelemetryOwnership openTelemetryOwnership;
    private final ContentCapturePolicy contentCapturePolicy;
    private final ExceptionCapturePolicy exceptionCapturePolicy;
    private final LangfuseOtelRuntime runtime;

    LangfuseOtel(SdkTracerProvider tracerProvider, OpenTelemetry openTelemetry,
                 Object langfuseClient, boolean noop) {
        this(tracerProvider, openTelemetry, langfuseClient, noop,
                tracerProvider != null
                        ? OpenTelemetryOwnership.OWNED
                        : (noop ? OpenTelemetryOwnership.NONE : OpenTelemetryOwnership.EXTERNAL),
                ContentCapturePolicy.captureAll(),
                ExceptionCapturePolicy.typeOnly(),
                LangfuseOtelRuntime.unmonitored(
                        tracerProvider != null,
                        noop
                                ? LangfuseOtelStatus.NoopReason.INITIALIZATION_FAILURE
                                : LangfuseOtelStatus.NoopReason.NONE));
    }

    private LangfuseOtel(SdkTracerProvider ownedTracerProvider, OpenTelemetry openTelemetry,
                         Object langfuseClient, boolean noop,
                         OpenTelemetryOwnership openTelemetryOwnership,
                         ContentCapturePolicy contentCapturePolicy,
                         ExceptionCapturePolicy exceptionCapturePolicy,
                         LangfuseOtelRuntime runtime) {
        this.ownedTracerProvider = ownedTracerProvider;
        this.tracer = openTelemetry.getTracer(TRACER_NAME, LIB_VERSION);
        this.langfuseClient = langfuseClient;
        this.noop = noop;
        this.openTelemetryOwnership = openTelemetryOwnership;
        this.contentCapturePolicy = Objects.requireNonNull(contentCapturePolicy, "contentCapturePolicy");
        this.exceptionCapturePolicy = Objects.requireNonNull(exceptionCapturePolicy, "exceptionCapturePolicy");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    private static LangfuseOtel createNoop(ContentCapturePolicy contentCapturePolicy,
                                            ExceptionCapturePolicy exceptionCapturePolicy,
                                            LangfuseOtelStatus.NoopReason reason) {
        return new LangfuseOtel(null, OpenTelemetry.noop(), null, true,
                OpenTelemetryOwnership.NONE, contentCapturePolicy, exceptionCapturePolicy,
                LangfuseOtelRuntime.unmonitored(false, reason));
    }

    /**
     * Returns the optional Langfuse Java client retained by this instance. Fail-safe no-op
     * instances return {@code null}.
     *
     * @return the client, or {@code null}
     */
    public Object getLangfuseClient() {
        return langfuseClient;
    }

    /**
     * Creates a builder for a standalone OpenTelemetry pipeline.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder that uses an application-provided OpenTelemetry instance.
     * This mode does not create an SDK or exporter and never flushes or shuts down the
     * supplied OpenTelemetry instance. The application remains its lifecycle owner.
     * Standalone transport settings such as API keys, host, and service name are ignored.
     *
     * @param openTelemetry the application-owned OpenTelemetry instance
     * @return a builder using the supplied instance
     * @throws NullPointerException if {@code openTelemetry} is {@code null}
     */
    public static Builder externalBuilder(OpenTelemetry openTelemetry) {
        Builder builder = new Builder();
        builder.externalOpenTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
        return builder;
    }

    private static String resolveLibraryVersion() {
        String version = LangfuseOtel.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    /**
     * Returns this integration's tracer.
     *
     * @return the tracer, including for no-op instances
     */
    public Tracer getTracer() {
        return tracer;
    }

    /**
     * Returns whether construction fell back to a no-op instance.
     *
     * @return {@code true} for a no-op instance
     */
    public boolean isNoop() {
        return noop;
    }

    /**
     * Returns the lifecycle ownership mode for the OpenTelemetry instance in use.
     *
     * @return the ownership mode
     */
    public OpenTelemetryOwnership getOpenTelemetryOwnership() {
        return openTelemetryOwnership;
    }

    /**
     * Returns whether this instance created and owns its OpenTelemetry SDK.
     *
     * @return {@code true} when this instance owns the SDK
     */
    public boolean ownsOpenTelemetry() {
        return openTelemetryOwnership == OpenTelemetryOwnership.OWNED;
    }

    /**
     * Returns an immutable snapshot of ownership, fallback, exporter, queue, and flush state.
     * Exporter and queue signals are deliberately not inferred for application-owned pipelines.
     *
     * @return the current operational snapshot
     */
    public LangfuseOtelStatus getStatus() {
        return runtime.snapshot(openTelemetryOwnership, noop);
    }

    /**
     * Returns the policy applied only to content recorded by automatic instrumentation.
     *
     * @return the content capture policy
     */
    public ContentCapturePolicy getContentCapturePolicy() {
        return contentCapturePolicy;
    }

    /**
     * Returns the policy applied to exceptions recorded by automatic instrumentation.
     *
     * @return the exception capture policy
     */
    public ExceptionCapturePolicy getExceptionCapturePolicy() {
        return exceptionCapturePolicy;
    }

    /**
     * Safely records automatic-instrumentation input according to the configured policy.
     *
     * @param span destination span; {@code null} is ignored
     * @param input input value
     */
    public void recordInput(Span span, Object input) {
        recordContent(span, ContentCaptureType.INPUT, LangfuseAttributes.OBSERVATION_INPUT, input);
    }

    /**
     * Safely records automatic-instrumentation output according to the configured policy.
     *
     * @param span destination span; {@code null} is ignored
     * @param output output value
     */
    public void recordOutput(Span span, Object output) {
        recordContent(span, ContentCaptureType.OUTPUT, LangfuseAttributes.OBSERVATION_OUTPUT, output);
    }

    /**
     * Safely records generation input according to the automatic content policy.
     *
     * @param generation destination generation; {@code null} is ignored
     * @param input input value
     */
    public void recordInput(LangfuseGeneration generation, Object input) {
        if (generation != null) {
            recordInput(generation.getSpan(), input);
        }
    }

    /**
     * Safely records generation output according to the automatic content policy.
     *
     * @param generation destination generation; {@code null} is ignored
     * @param output output value
     */
    public void recordOutput(LangfuseGeneration generation, Object output) {
        if (generation != null) {
            recordOutput(generation.getSpan(), output);
        }
    }

    /**
     * Safely records an automatic-instrumentation exception according to the configured policy.
     *
     * @param span destination span; {@code null} is ignored
     * @param throwable exception to record; {@code null} is ignored
     */
    public void recordException(Span span, Throwable throwable) {
        ExceptionRecorder.record(span, throwable, exceptionCapturePolicy);
    }

    /**
     * Safely records a generation exception according to the automatic exception policy.
     *
     * @param generation destination generation; {@code null} is ignored
     * @param throwable exception to record; {@code null} is ignored
     */
    public void recordException(LangfuseGeneration generation, Throwable throwable) {
        if (generation != null) {
            recordException(generation.getSpan(), throwable);
        }
    }

    private void recordContent(Span span, ContentCaptureType type, String attributeName, Object value) {
        if (span == null) {
            return;
        }
        try {
            String captured = contentCapturePolicy.capture(type, value);
            if (captured != null) {
                span.setAttribute(attributeName, captured);
            }
        } catch (Throwable ignored) {
            // Automatic instrumentation must never affect host application behavior.
        }
    }

    /**
     * Creates a synchronous, thread-bound trace scope. The caller must close it on the creating
     * thread, after its children, using try-with-resources or {@code end()}.
     *
     * @param name trace name
     * @return the new trace
     */
    public LangfuseTrace trace(String name) {
        return new LangfuseTrace(tracer, name);
    }

    /**
     * Creates a trace, runs an action, and closes the trace.
     *
     * @param name trace name
     * @param action action to run; runtime exceptions propagate after recording
     */
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

    /**
     * Requests a local flush only for the internally owned SDK; external and no-op modes do nothing.
     * Local flush completion does not guarantee that the remote endpoint accepted every span.
     */
    public void flush() {
        if (openTelemetryOwnership == OpenTelemetryOwnership.OWNED && ownedTracerProvider != null) {
            long sequence = runtime.beginFlush();
            try {
                LangfuseOtelStatus.FlushState state = awaitFlush(
                        ownedTracerProvider.forceFlush(), 10, TimeUnit.SECONDS);
                runtime.completeFlush(sequence, state);
            } catch (RuntimeException | Error e) {
                runtime.completeFlush(sequence, LangfuseOtelStatus.FlushState.FAILED);
                throw e;
            }
        }
    }

    static LangfuseOtelStatus.FlushState awaitFlush(CompletableResultCode result,
                                                     long timeout,
                                                     TimeUnit unit) {
        if (result == null) {
            return LangfuseOtelStatus.FlushState.FAILED;
        }
        result.join(timeout, unit);
        if (!result.isDone()) {
            if (Thread.currentThread().isInterrupted()) {
                return LangfuseOtelStatus.FlushState.FAILED;
            }
            return LangfuseOtelStatus.FlushState.TIMED_OUT;
        }
        return result.isSuccess()
                ? LangfuseOtelStatus.FlushState.SUCCEEDED
                : LangfuseOtelStatus.FlushState.FAILED;
    }

    /** Shuts down only the internally owned SDK; the application retains external lifecycle control. */
    @Override
    public void close() {
        if (openTelemetryOwnership == OpenTelemetryOwnership.OWNED && ownedTracerProvider != null) {
            ownedTracerProvider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    /** Configures a Langfuse OpenTelemetry integration. */
    public static class Builder {

        private String publicKey;
        private String secretKey;
        private String host = DEFAULT_HOST;
        private String serviceName = "langfuse-app";
        private String environment;
        private String release;
        private Object langfuseClient;
        private boolean failSafe = true;
        private boolean allowInsecureHttpForDevelopment;
        private OpenTelemetry externalOpenTelemetry;
        private ContentCapturePolicy contentCapturePolicy = ContentCapturePolicy.metadataOnly();
        private ExceptionCapturePolicy exceptionCapturePolicy = ExceptionCapturePolicy.typeOnly();

        private Builder() {}

        /**
         * Sets the Langfuse public API key for the standalone exporter.
         *
         * @param publicKey public API key
         * @return this builder
         */
        public Builder publicKey(String publicKey) { this.publicKey = publicKey; return this; }

        /**
         * Sets the Langfuse secret API key for the standalone exporter.
         *
         * @param secretKey secret API key
         * @return this builder
         */
        public Builder secretKey(String secretKey) { this.secretKey = secretKey; return this; }

        /**
         * Sets the Langfuse base URL. HTTPS is required by default.
         *
         * @param host absolute HTTP(S) base URL
         * @return this builder
         */
        public Builder host(String host) { this.host = host; return this; }

        /**
         * Sets the OpenTelemetry service name.
         *
         * @param serviceName service name
         * @return this builder
         */
        public Builder serviceName(String serviceName) { this.serviceName = serviceName; return this; }

        /**
         * Sets the deployment environment resource attribute.
         *
         * @param environment environment name
         * @return this builder
         */
        public Builder environment(String environment) { this.environment = environment; return this; }

        /**
         * Sets the release resource attribute.
         *
         * @param release release identifier
         * @return this builder
         */
        public Builder release(String release) { this.release = release; return this; }

        /**
         * Supplies an optional Langfuse Java client for prompt helpers.
         *
         * @param langfuseClient client instance
         * @return this builder
         */
        public Builder langfuseClient(Object langfuseClient) { this.langfuseClient = langfuseClient; return this; }

        /**
         * Controls whether invalid configuration or initialization failure yields a no-op instance.
         *
         * @param failSafe whether to fall back to no-op mode
         * @return this builder
         */
        public Builder failSafe(boolean failSafe) { this.failSafe = failSafe; return this; }

        /**
         * Allows a plaintext HTTP standalone endpoint on {@code localhost} or a literal loopback
         * address for local development only.
         * Production endpoints should always use HTTPS because API credentials are sent using
         * the HTTP {@code Authorization} header.
         *
         * @param allow whether local loopback HTTP is allowed
         * @return this builder
         */
        public Builder allowInsecureHttpForDevelopment(boolean allow) {
            this.allowInsecureHttpForDevelopment = allow;
            return this;
        }
        /**
         * Sets the policy for automatically captured model content.
         *
         * @param contentCapturePolicy capture policy
         * @return this builder
         * @throws NullPointerException if {@code contentCapturePolicy} is {@code null}
         */
        public Builder contentCapturePolicy(ContentCapturePolicy contentCapturePolicy) {
            this.contentCapturePolicy = Objects.requireNonNull(contentCapturePolicy, "contentCapturePolicy");
            return this;
        }
        /**
         * Sets the policy for automatically captured exceptions.
         *
         * @param exceptionCapturePolicy capture policy
         * @return this builder
         * @throws NullPointerException if {@code exceptionCapturePolicy} is {@code null}
         */
        public Builder exceptionCapturePolicy(ExceptionCapturePolicy exceptionCapturePolicy) {
            this.exceptionCapturePolicy = Objects.requireNonNull(exceptionCapturePolicy, "exceptionCapturePolicy");
            return this;
        }

        /**
         * Builds the integration.
         *
         * @return an active integration, or a no-op instance when fail-safe construction recovers
         * @throws RuntimeException if initialization fails while fail-safe construction is disabled
         */
        public LangfuseOtel build() {
            if (externalOpenTelemetry != null) {
                try {
                    return new LangfuseOtel(null, externalOpenTelemetry, langfuseClient, false,
                            OpenTelemetryOwnership.EXTERNAL, contentCapturePolicy, exceptionCapturePolicy,
                            LangfuseOtelRuntime.unmonitored(false, LangfuseOtelStatus.NoopReason.NONE));
                } catch (RuntimeException e) {
                    if (failSafe) {
                        log.warn("Failed to initialize Langfuse with external OpenTelemetry. Running in no-op mode.", e);
                        return LangfuseOtel.createNoop(contentCapturePolicy, exceptionCapturePolicy,
                                LangfuseOtelStatus.NoopReason.INITIALIZATION_FAILURE);
                    }
                    throw e;
                }
            }

            // The default fail-safe mode keeps configuration errors from crashing the host application.
            if (publicKey == null || publicKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
                if (failSafe) {
                    log.warn("Langfuse API keys not configured. Running in no-op mode — traces will not be sent.");
                    return LangfuseOtel.createNoop(contentCapturePolicy, exceptionCapturePolicy,
                            LangfuseOtelStatus.NoopReason.MISSING_CREDENTIALS);
                }
                throw new IllegalArgumentException("publicKey and secretKey are required");
            }

            OtlpHttpSpanExporter exporter = null;
            SdkTracerProvider tracerProvider = null;
            try {
                String endpoint = buildOtlpEndpoint(host, allowInsecureHttpForDevelopment);
                String authHeader = "Basic " + Base64.getEncoder()
                        .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));

                LangfuseOtelRuntime runtime = LangfuseOtelRuntime.managed();
                LangfuseOtelRuntimeMeterProvider runtimeMeterProvider =
                        new LangfuseOtelRuntimeMeterProvider(runtime);

                exporter = OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint)
                        .addHeader("Authorization", authHeader)
                        .addHeader("x-langfuse-ingestion-version", "4")
                        .setMeterProvider(runtimeMeterProvider)
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

                tracerProvider = SdkTracerProvider.builder()
                        .setResource(resource)
                        // Langfuse owns this provider exclusively, so its sampling decision must not be
                        // inherited from an unrelated upstream trace. Without this, the SDK default
                        // parentBased(alwaysOn) drops every LLM span whenever a surrounding agent- or
                        // Micrometer-created parent span was head-sampled away.
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(new LangfuseContextSpanProcessor())
                        .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                                .setMeterProvider(runtimeMeterProvider)
                                .build())
                        .build();

                OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerProvider)
                        .build();

                return new LangfuseOtel(tracerProvider, otel, langfuseClient, false,
                        OpenTelemetryOwnership.OWNED, contentCapturePolicy, exceptionCapturePolicy, runtime);
            } catch (Exception e) {
                cleanUpFailedBuild(tracerProvider, exporter);
                if (failSafe) {
                    log.warn("Failed to initialize Langfuse OTel. Running in no-op mode.", e);
                    return LangfuseOtel.createNoop(contentCapturePolicy, exceptionCapturePolicy,
                            LangfuseOtelStatus.NoopReason.INITIALIZATION_FAILURE);
                }
                throw e;
            }
        }

        private static void cleanUpFailedBuild(SdkTracerProvider tracerProvider,
                                               OtlpHttpSpanExporter exporter) {
            try {
                if (tracerProvider != null) {
                    tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
                } else if (exporter != null) {
                    exporter.shutdown().join(10, TimeUnit.SECONDS);
                }
            } catch (RuntimeException ignored) {
                // Preserve the initialization failure that caused this cleanup.
            }
        }

        private static String buildOtlpEndpoint(String configuredHost, boolean allowInsecureHttp) {
            if (configuredHost == null || configuredHost.isEmpty()) {
                throw new IllegalArgumentException("host must be a non-empty absolute HTTP(S) URI");
            }

            final URI baseUri;
            try {
                baseUri = new URI(configuredHost);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("host must be a valid absolute HTTP(S) URI", e);
            }

            String scheme = baseUri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                throw new IllegalArgumentException("host scheme must be http or https");
            }
            if (baseUri.isOpaque() || baseUri.getHost() == null || baseUri.getHost().isEmpty()) {
                throw new IllegalArgumentException("host URI must include a network host");
            }
            if (baseUri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("host URI must not include user-info");
            }
            if (baseUri.getRawQuery() != null) {
                throw new IllegalArgumentException("host URI must not include a query");
            }
            if (baseUri.getRawFragment() != null) {
                throw new IllegalArgumentException("host URI must not include a fragment");
            }
            if (baseUri.getPort() == 0 || baseUri.getPort() > 65_535) {
                throw new IllegalArgumentException("host URI port must be between 1 and 65535");
            }
            if (scheme.equalsIgnoreCase("http")) {
                if (!allowInsecureHttp) {
                    throw new IllegalArgumentException("HTTP host is disabled by default; use HTTPS or explicitly call "
                            + "allowInsecureHttpForDevelopment(true) for local development only");
                }
                if (!isLiteralLoopbackHost(baseUri.getHost())) {
                    throw new IllegalArgumentException("Development HTTP is restricted to localhost or a literal "
                            + "loopback address; remote hosts require HTTPS");
                }
            }

            String base = baseUri.toASCIIString();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + OTEL_PATH;
        }

        private static boolean isLiteralLoopbackHost(String host) {
            if (host.equalsIgnoreCase("localhost")) {
                return true;
            }

            String unwrappedHost = host;
            if (host.startsWith("[") && host.endsWith("]")) {
                unwrappedHost = host.substring(1, host.length() - 1);
            }
            if (unwrappedHost.equals("::1") || unwrappedHost.equals("0:0:0:0:0:0:0:1")) {
                return true;
            }

            String[] octets = unwrappedHost.split("\\.", -1);
            if (octets.length != 4 || !octets[0].equals("127")) {
                return false;
            }
            for (String octet : octets) {
                if (octet.isEmpty()) return false;
                for (int i = 0; i < octet.length(); i++) {
                    if (!Character.isDigit(octet.charAt(i))) return false;
                }
                try {
                    if (Integer.parseInt(octet) > 255) return false;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            return true;
        }
    }
}

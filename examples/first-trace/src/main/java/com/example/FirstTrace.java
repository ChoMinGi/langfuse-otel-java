package com.example;

import io.github.chomingi.langfuse.otel.ContentCapturePolicy;
import io.github.chomingi.langfuse.otel.LangfuseObservation;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.github.chomingi.langfuse.otel.ObservationType;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.net.URI;
import java.util.UUID;

/** Sends fixed synthetic content. No model API, API key, or provider token counts. */
public final class FirstTrace {
    static final String QUESTION = "How do I trace a Java application?";
    static final String CONTEXT = "Use langfuse-otel-core for explicit observations in Java 11+.";
    static final String ANSWER = "Create a root observation, add child steps, and close each observation.";

    private FirstTrace() {}

    public static void main(String[] args) throws Exception {
        String host = requiredEnv("LANGFUSE_HOST").replaceAll("/+$", "");
        validateHost(host);
        String publicKey = requiredEnv("LANGFUSE_PUBLIC_KEY");
        String secretKey = requiredEnv("LANGFUSE_SECRET_KEY");
        String run = UUID.randomUUID().toString();
        String traceId;

        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .host(host).publicKey(publicKey).secretKey(secretKey)
                .serviceName("java-first-trace")
                // Only fixed demo content is captured. The library defaults to metadata only.
                .contentCapturePolicy(ContentCapturePolicy.captureAll())
                .failSafe(false).build()) {
            traceId = emit(langfuse, run);
            langfuse.flush();
        }
        System.out.println("Sent synthetic trace: " + traceId);
        System.out.println("Waiting up to 90 seconds for Langfuse ingestion...");
        ReadBack.verify(host, publicKey, secretKey, traceId, run);
    }

    static String emit(LangfuseOtel langfuse, String run) {
        LangfuseTraceContext request = LangfuseTraceContext.builder()
                .traceName("java-first-trace").sessionId("first-trace-" + run)
                .tags("first-trace", "synthetic").environment("demo")
                .metadata("demo", "synthetic-no-model-call").metadata("run", run).build();

        try (LangfuseObservation root = langfuse.observation("java-first-trace", ObservationType.CHAIN)
                .parent(Context.root()).traceContext(request).start()) {
            root.input(QUESTION);
            try {
                try (LangfuseObservation lookup = langfuse.observation("lookup-docs", ObservationType.TOOL)
                        .parent(root.context()).start()) {
                    lookup.input("langfuse-otel-java getting started").output(CONTEXT);
                }
                try (LangfuseObservation answer = langfuse.observation("answer-question", ObservationType.GENERATION)
                        .parent(root.context()).start()) {
                    answer.model("demo-fixture").input(QUESTION + "\nContext: " + CONTEXT).output(ANSWER);
                    // This is a fixture, so do not invent provider usage, cost, or first-token timing.
                }
                root.output(ANSWER);
                return Span.fromContext(root.context()).getSpanContext().getTraceId();
            } catch (RuntimeException | Error failure) {
                root.fail(failure);
                throw failure;
            }
        }
    }

    static void validateHost(String host) {
        URI uri = URI.create(host);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getPath().isEmpty()) {
            throw new IllegalArgumentException("LANGFUSE_HOST must be an HTTPS origin, e.g. https://cloud.langfuse.com");
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Set " + name + " before running the example (see examples/first-trace/README.md)");
        }
        return value;
    }
}

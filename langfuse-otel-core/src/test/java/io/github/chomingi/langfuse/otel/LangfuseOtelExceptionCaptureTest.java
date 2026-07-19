package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LangfuseOtelExceptionCaptureTest {

    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private SdkTracerProvider externalTracerProvider;
    private InMemorySpanExporter exporter;

    @AfterEach
    void shutDownExternalProvider() {
        if (externalTracerProvider != null) {
            externalTracerProvider.shutdown();
        }
    }

    @Test
    void automaticExceptionHelperRecordsTypeOnlyByDefault() {
        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry()).build()) {
            Span span = langfuse.getTracer().spanBuilder("type-only").startSpan();
            langfuse.recordException(span, new IllegalStateException("secret-message"));
            span.end();

            assertThat(langfuse.getExceptionCapturePolicy().isMessageCaptureEnabled()).isFalse();
            assertThat(langfuse.getExceptionCapturePolicy().isStackTraceCaptureEnabled()).isFalse();
        }

        SpanData span = onlySpan();
        EventData event = onlyExceptionEvent(span);
        assertThat(event.getAttributes().get(EXCEPTION_TYPE)).isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getAttributes().get(EXCEPTION_MESSAGE)).isNull();
        assertThat(event.getAttributes().get(EXCEPTION_STACKTRACE)).isNull();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEmpty();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(
                LangfuseAttributes.OBSERVATION_STATUS_MESSAGE)))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void optInExceptionDetailsAreRedactedAndBounded() {
        ExceptionCapturePolicy policy = ExceptionCapturePolicy.builder()
                .captureMessage(true)
                .captureStackTrace(true)
                .maxLength(48)
                .redactor((type, content) -> content.replace("secret-value", "[REDACTED]"))
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .exceptionCapturePolicy(policy)
                .build()) {
            try (LangfuseGeneration generation =
                         new LangfuseGeneration(langfuse.getTracer(), "detailed")) {
                langfuse.recordException(generation, new IllegalArgumentException("secret-value"));
            }
        }

        EventData event = onlyExceptionEvent(onlySpan());
        String message = event.getAttributes().get(EXCEPTION_MESSAGE);
        String stackTrace = event.getAttributes().get(EXCEPTION_STACKTRACE);
        assertThat(message).isEqualTo("[REDACTED]");
        assertThat(stackTrace)
                .doesNotContain("secret-value")
                .hasSizeLessThanOrEqualTo(48);
    }

    @Test
    void stackTraceCaptureNeverIncludesMessagesFromThrowableGraph() {
        ExceptionCapturePolicy policy = ExceptionCapturePolicy.builder()
                .captureStackTrace(true)
                .build();
        IllegalArgumentException cause = new IllegalArgumentException("cause-secret");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("CauseType", "causeMethod", "CauseType.java", 22)
        });
        IllegalStateException error = new IllegalStateException("top-secret", cause);
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("TopType", "topMethod", "TopType.java", 11)
        });
        UnsupportedOperationException suppressed =
                new UnsupportedOperationException("suppressed-secret");
        suppressed.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("SuppressedType", "suppressedMethod", "SuppressedType.java", 33)
        });
        error.addSuppressed(suppressed);

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .exceptionCapturePolicy(policy)
                .build()) {
            Span span = langfuse.getTracer().spanBuilder("message-free-stack").startSpan();
            langfuse.recordException(span, error);
            span.end();
        }

        EventData event = onlyExceptionEvent(onlySpan());
        String stackTrace = event.getAttributes().get(EXCEPTION_STACKTRACE);
        assertThat(event.getAttributes().get(EXCEPTION_MESSAGE)).isNull();
        assertThat(stackTrace)
                .contains(IllegalStateException.class.getName())
                .contains(IllegalArgumentException.class.getName())
                .contains(UnsupportedOperationException.class.getName())
                .contains("Caused by:")
                .contains("Suppressed:")
                .doesNotContain("top-secret", "cause-secret", "suppressed-secret");
    }

    @Test
    void typeOnlyPolicyDoesNotEvaluateThrowableMessage() {
        MessageFailingException error = new MessageFailingException();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry()).build()) {
            Span span = langfuse.getTracer().spanBuilder("lazy-type-only").startSpan();
            assertThatCode(() -> langfuse.recordException(span, error)).doesNotThrowAnyException();
            span.end();
        }

        assertThat(error.messageRequested).isFalse();
        EventData event = onlyExceptionEvent(onlySpan());
        assertThat(event.getAttributes().get(EXCEPTION_TYPE))
                .isEqualTo(MessageFailingException.class.getName());
    }

    @Test
    void instrumentationErrorCannotReplaceTheApplicationThrowable() {
        ExceptionCapturePolicy policy = ExceptionCapturePolicy.builder()
                .captureMessage(true)
                .redactor((type, content) -> {
                    throw new AssertionError("instrumentation failure");
                })
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .exceptionCapturePolicy(policy)
                .build()) {
            Span span = langfuse.getTracer().spanBuilder("error-safe").startSpan();
            assertThatCode(() -> langfuse.recordException(span, new RuntimeException("application failure")))
                    .doesNotThrowAnyException();
            span.end();
        }
    }

    @Test
    void redactorFailureKeepsTypeAndDoesNotAffectHostApplication() {
        ExceptionCapturePolicy policy = ExceptionCapturePolicy.builder()
                .captureMessage(true)
                .captureStackTrace(true)
                .redactor((type, content) -> {
                    throw new IllegalStateException("redactor failed");
                })
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .exceptionCapturePolicy(policy)
                .build()) {
            Span span = langfuse.getTracer().spanBuilder("redactor-failure").startSpan();
            assertThatCode(() -> langfuse.recordException(span, new RuntimeException("secret")))
                    .doesNotThrowAnyException();
            span.end();
        }

        EventData event = onlyExceptionEvent(onlySpan());
        assertThat(event.getAttributes().get(EXCEPTION_TYPE)).isEqualTo(RuntimeException.class.getName());
        assertThat(event.getAttributes().get(EXCEPTION_MESSAGE)).isNull();
        assertThat(event.getAttributes().get(EXCEPTION_STACKTRACE)).isNull();
    }

    private OpenTelemetry externalOpenTelemetry() {
        exporter = InMemorySpanExporter.create();
        externalTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(externalTracerProvider)
                .build();
    }

    private SpanData onlySpan() {
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        return exporter.getFinishedSpanItems().get(0);
    }

    private static EventData onlyExceptionEvent(SpanData span) {
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().get(0).getName()).isEqualTo("exception");
        return span.getEvents().get(0);
    }

    private static final class MessageFailingException extends RuntimeException {
        private boolean messageRequested;

        @Override
        public String getMessage() {
            messageRequested = true;
            throw new AssertionError("getMessage must not be called");
        }
    }
}

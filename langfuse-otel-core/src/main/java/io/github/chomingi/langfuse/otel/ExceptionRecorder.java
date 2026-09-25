package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Internal fail-safe exception event recorder shared by manual and automatic instrumentation. */
final class ExceptionRecorder {

    private static final String EXCEPTION_EVENT_NAME = "exception";
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");
    private static final ExceptionCapturePolicy TYPE_ONLY = ExceptionCapturePolicy.typeOnly();

    private ExceptionRecorder() {}

    static void recordTypeOnly(Span span, Throwable throwable) {
        record(span, throwable, TYPE_ONLY);
    }

    static void record(Span span, Throwable throwable, ExceptionCapturePolicy policy) {
        if (span == null || throwable == null || policy == null) {
            return;
        }

        try {
            prepare(throwable, policy, false).applyTo(span);
        } catch (Throwable ignored) {
            // Preserve the legacy fail-safe contract, including its treatment of fatal errors.
        }
    }

    // Runs user code before the observation acquires its mutation lock.
    static ExceptionDetails prepare(Throwable throwable, ExceptionCapturePolicy policy, boolean propagateFatal) {
        String type = throwable.getClass().getName();
        String message = policy.isMessageCaptureEnabled()
                ? policy.capture(ExceptionCaptureType.MESSAGE, safeMessage(throwable, propagateFatal), propagateFatal)
                : null;
        String stackTrace = policy.isStackTraceCaptureEnabled()
                ? policy.capture(ExceptionCaptureType.STACK_TRACE, renderStackTrace(throwable, propagateFatal), propagateFatal)
                : null;
        AttributesBuilder attributes = Attributes.builder().put(EXCEPTION_TYPE, type);
        if (message != null) attributes.put(EXCEPTION_MESSAGE, message);
        if (stackTrace != null) attributes.put(EXCEPTION_STACKTRACE, stackTrace);
        return new ExceptionDetails(type, message, attributes.build());
    }

    static final class ExceptionDetails {
        private final String type;
        private final String message;
        private final Attributes attributes;

        private ExceptionDetails(String type, String message, Attributes attributes) {
            this.type = type;
            this.message = message;
            this.attributes = attributes;
        }

        void applyTo(Span span) {
            span.addEvent(EXCEPTION_EVENT_NAME, attributes);
            if (message != null) span.setStatus(StatusCode.ERROR, message);
            else span.setStatus(StatusCode.ERROR);
            span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message != null ? message : type);
            span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        }
    }

    private static String safeMessage(Throwable throwable, boolean propagateFatal) {
        try {
            return throwable.getMessage();
        } catch (Throwable failure) {
            if (propagateFatal) ObservationFailureSupport.rethrowIfFatal(failure);
            return null;
        }
    }

    private static String renderStackTrace(Throwable throwable, boolean propagateFatal) {
        try {
            StringBuilder rendered = new StringBuilder(512);
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            appendThrowable(rendered, throwable, "", "", visited, propagateFatal);
            return rendered.toString();
        } catch (Throwable failure) {
            if (propagateFatal) ObservationFailureSupport.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Renders throwable types and frames without invoking {@code Throwable#toString()} or
     * including any message. Causes and suppressed exceptions follow the familiar JDK layout,
     * while identity tracking prevents hostile or malformed cause graphs from recursing forever.
     */
    private static void appendThrowable(StringBuilder rendered, Throwable throwable,
                                        String caption, String prefix, Set<Throwable> visited, boolean propagateFatal) {
        if (throwable == null) {
            return;
        }

        String type = throwable.getClass().getName();
        if (!visited.add(throwable)) {
            rendered.append(prefix)
                    .append(caption)
                    .append("[CIRCULAR REFERENCE: ")
                    .append(type)
                    .append("]\n");
            return;
        }

        rendered.append(prefix).append(caption).append(type).append('\n');
        for (StackTraceElement frame : safeStackTrace(throwable, propagateFatal)) {
            rendered.append(prefix).append("\tat ").append(frame).append('\n');
        }
        for (Throwable suppressed : safeSuppressed(throwable, propagateFatal)) {
            appendThrowable(rendered, suppressed, "Suppressed: ", prefix + "\t", visited, propagateFatal);
        }
        appendThrowable(rendered, safeCause(throwable, propagateFatal), "Caused by: ", prefix, visited, propagateFatal);
    }

    private static StackTraceElement[] safeStackTrace(Throwable throwable, boolean propagateFatal) {
        try {
            StackTraceElement[] stackTrace = throwable.getStackTrace();
            return stackTrace != null ? stackTrace : new StackTraceElement[0];
        } catch (Throwable failure) {
            if (propagateFatal) ObservationFailureSupport.rethrowIfFatal(failure);
            return new StackTraceElement[0];
        }
    }

    private static Throwable[] safeSuppressed(Throwable throwable, boolean propagateFatal) {
        try {
            Throwable[] suppressed = throwable.getSuppressed();
            return suppressed != null ? suppressed : new Throwable[0];
        } catch (Throwable failure) {
            if (propagateFatal) ObservationFailureSupport.rethrowIfFatal(failure);
            return new Throwable[0];
        }
    }

    private static Throwable safeCause(Throwable throwable, boolean propagateFatal) {
        try {
            return throwable.getCause();
        } catch (Throwable failure) {
            if (propagateFatal) ObservationFailureSupport.rethrowIfFatal(failure);
            return null;
        }
    }
}

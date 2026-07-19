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
            String type = throwable.getClass().getName();
            String message = policy.isMessageCaptureEnabled()
                    ? policy.capture(ExceptionCaptureType.MESSAGE, safeMessage(throwable))
                    : null;
            String stackTrace = policy.isStackTraceCaptureEnabled()
                    ? policy.capture(ExceptionCaptureType.STACK_TRACE, renderStackTrace(throwable))
                    : null;

            AttributesBuilder eventAttributes = Attributes.builder().put(EXCEPTION_TYPE, type);
            if (message != null) {
                eventAttributes.put(EXCEPTION_MESSAGE, message);
            }
            if (stackTrace != null) {
                eventAttributes.put(EXCEPTION_STACKTRACE, stackTrace);
            }
            span.addEvent(EXCEPTION_EVENT_NAME, eventAttributes.build());

            if (message != null) {
                span.setStatus(StatusCode.ERROR, message);
                span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
            } else {
                span.setStatus(StatusCode.ERROR);
                span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, type);
            }
            span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        } catch (Throwable ignored) {
            // Observability must never change host application behavior.
        }
    }

    private static String safeMessage(Throwable throwable) {
        try {
            return throwable.getMessage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String renderStackTrace(Throwable throwable) {
        try {
            StringBuilder rendered = new StringBuilder(512);
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            appendThrowable(rendered, throwable, "", "", visited);
            return rendered.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Renders throwable types and frames without invoking {@code Throwable#toString()} or
     * including any message. Causes and suppressed exceptions follow the familiar JDK layout,
     * while identity tracking prevents hostile or malformed cause graphs from recursing forever.
     */
    private static void appendThrowable(StringBuilder rendered, Throwable throwable,
                                        String caption, String prefix, Set<Throwable> visited) {
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
        for (StackTraceElement frame : safeStackTrace(throwable)) {
            rendered.append(prefix).append("\tat ").append(frame).append('\n');
        }
        for (Throwable suppressed : safeSuppressed(throwable)) {
            appendThrowable(rendered, suppressed, "Suppressed: ", prefix + "\t", visited);
        }
        appendThrowable(rendered, safeCause(throwable), "Caused by: ", prefix, visited);
    }

    private static StackTraceElement[] safeStackTrace(Throwable throwable) {
        try {
            StackTraceElement[] stackTrace = throwable.getStackTrace();
            return stackTrace != null ? stackTrace : new StackTraceElement[0];
        } catch (Throwable ignored) {
            return new StackTraceElement[0];
        }
    }

    private static Throwable[] safeSuppressed(Throwable throwable) {
        try {
            Throwable[] suppressed = throwable.getSuppressed();
            return suppressed != null ? suppressed : new Throwable[0];
        } catch (Throwable ignored) {
            return new Throwable[0];
        }
    }

    private static Throwable safeCause(Throwable throwable) {
        try {
            return throwable.getCause();
        } catch (Throwable ignored) {
            return null;
        }
    }
}

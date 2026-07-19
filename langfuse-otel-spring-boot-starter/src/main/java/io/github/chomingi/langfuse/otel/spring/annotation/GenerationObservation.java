package io.github.chomingi.langfuse.otel.spring.annotation;

import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

/** A raw-span observation that never retains a thread-affine Scope across async boundaries. */
final class GenerationObservation {

    private final LangfuseOtel langfuseOtel;
    private final Span span;
    private final Context spanContext;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    private GenerationObservation(LangfuseOtel langfuseOtel, Span span, Context spanContext) {
        this.langfuseOtel = langfuseOtel;
        this.span = span;
        this.spanContext = spanContext;
    }

    static GenerationObservation start(LangfuseOtel langfuseOtel,
                                       Context parentContext,
                                       LangfuseTraceContext traceContext,
                                       String name,
                                       String operation,
                                       String model,
                                       String system) {
        Context effectiveParent = parentContext == null ? Context.current() : parentContext;
        if (traceContext != null) {
            effectiveParent = LangfuseContext.storeIn(effectiveParent, traceContext);
        }

        Span createdSpan = null;
        try {
            io.opentelemetry.api.trace.SpanBuilder builder = langfuseOtel.getTracer()
                    .spanBuilder(name)
                    .setParent(effectiveParent)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME,
                            operation == null || operation.isEmpty() ? "chat" : operation);
            if (model != null && !model.isEmpty()) {
                builder.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, model);
            }
            if (system != null && !system.isEmpty()) {
                builder.setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, system);
            }
            createdSpan = builder.startSpan();
            LangfuseContext.applyTo(createdSpan, traceContext);
            return new GenerationObservation(langfuseOtel, createdSpan, effectiveParent.with(createdSpan));
        } catch (Throwable failure) {
            endSpanQuietly(createdSpan);
            rethrowIfFatal(failure);
            throw failure;
        }
    }

    Span span() {
        return span;
    }

    Context spanContext() {
        return spanContext;
    }

    Scope makeCurrent() {
        return spanContext.makeCurrent();
    }

    void completeSuccessfully(Object output) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        try {
            if (output != null) {
                langfuseOtel.recordOutput(span, output);
            }
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
        } finally {
            endSpanQuietly(span);
        }
    }

    void completeExceptionally(Throwable failure) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        try {
            langfuseOtel.recordException(span, failure);
        } catch (Throwable recordingFailure) {
            rethrowIfFatal(recordingFailure);
        } finally {
            endSpanQuietly(span);
        }
    }

    void cancel() {
        end();
    }

    void end() {
        if (ended.compareAndSet(false, true)) {
            endSpanQuietly(span);
        }
    }

    static void closeScopeQuietly(Scope scope) {
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
        }
    }

    static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
        if (failure instanceof LinkageError) {
            throw (LinkageError) failure;
        }
    }

    private static void endSpanQuietly(Span span) {
        if (span == null) {
            return;
        }
        try {
            span.end();
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
        }
    }
}

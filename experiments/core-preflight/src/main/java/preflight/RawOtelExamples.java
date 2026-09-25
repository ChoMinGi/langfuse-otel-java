package preflight;

import io.github.chomingi.langfuse.otel.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Executable consumer examples using only the existing public API; not a proposed SDK API. */
public final class RawOtelExamples {
    private RawOtelExamples() {}

    public static Span start(LangfuseOtel lf, String name, String type,
                             Context parent, LangfuseTraceContext snapshot) {
        Span span = lf.getTracer().spanBuilder(name).setParent(parent)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, type).startSpan();
        // Explicit copying is necessary with an application-owned SDK without a processor.
        LangfuseContext.applyTo(span, snapshot);
        return span;
    }

    public static String sync(LangfuseOtel lf, Context parent, LangfuseTraceContext snapshot,
                              String prompt, Supplier<String> model) {
        Span span = start(lf, "sync", "generation", parent, snapshot);
        try (Scope scope = parent.with(span).makeCurrent()) {
            lf.recordInput(span, prompt);
            String result = model.get();
            lf.recordOutput(span, result);
            return result;
        } catch (RuntimeException | Error failure) {
            lf.recordException(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    public static CompletionStage<String> async(LangfuseOtel lf, Context parent,
            LangfuseTraceContext snapshot, String prompt, Supplier<CompletionStage<String>> model) {
        Span span = start(lf, "async", "generation", parent, snapshot);
        try {
            lf.recordInput(span, prompt);
            CompletionStage<String> stage;
            // Scope covers submission only. Provider-owned executors require explicit propagation.
            try (Scope scope = parent.with(span).makeCurrent()) {
                stage = model.get();
            }
            stage.whenComplete((response, failure) -> {
                try {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof CancellationException) {
                        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "cancelled");
                    } else if (cause != null) {
                        lf.recordException(span, cause);
                    } else {
                        lf.recordOutput(span, response);
                    }
                } finally {
                    span.end();
                }
            });
            return stage; // Keeps the provider's stage identity; adds no timeout/cancellation engine.
        } catch (RuntimeException | Error failure) {
            lf.recordException(span, failure);
            span.end();
            throw failure;
        }
    }

    public static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null && failure.getCause() != failure) {
            failure = failure.getCause();
        }
        return failure;
    }
}

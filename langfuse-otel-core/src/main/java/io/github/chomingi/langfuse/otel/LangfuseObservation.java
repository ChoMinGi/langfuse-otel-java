package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Objects;

/**
 * An explicitly owned observation with a lifetime independent of any {@link Scope}.
 * Mutations and terminal operations may run on different threads. The first committed
 * {@link #end()}, {@link #fail(Throwable)} or {@link #cancel()} wins; subsequent mutations
 * are ignored. Closing ends the observation and never restores a thread's context.
 *
 * <p>Content and exception details use the integration's capture policies. User redactors
 * run outside the mutation lock; a result prepared concurrently with termination may be
 * discarded. Nonfatal instrumentation failures are contained. VirtualMachineError,
 * ThreadDeath and LinkageError raised by instrumentation are propagated.</p>
 *
 * <p>This object does not cancel provider work, own an executor, or end child observations.
 * It is not registered globally and has no Cleaner or timeout. The application must end it.
 * Direct writes to the underlying OTel span obtained through {@link #context()} and writes
 * from other SDK components are outside this object's mutation contract.</p>
 */
public final class LangfuseObservation implements AutoCloseable {
    private final Span span;
    private final Context context;
    private final ContentCapturePolicy contentPolicy;
    private final ExceptionCapturePolicy exceptionPolicy;
    private final Object lock = new Object();
    private volatile boolean ended;

    private LangfuseObservation(Span span, Context context, ContentCapturePolicy contentPolicy,
                               ExceptionCapturePolicy exceptionPolicy) {
        this.span = span;
        this.context = context;
        this.contentPolicy = contentPolicy;
        this.exceptionPolicy = exceptionPolicy;
    }

    /**
     * Returns the span context and immutable Langfuse snapshot for explicit propagation.
     * It remains usable after termination, including as a parent for later work.
     * @return the propagation context
     */
    public Context context() { return context; }

    /**
     * Makes this context current. Close the returned scope on the same thread, in reverse
     * nesting order, even if another thread ends the observation in the meantime.
     * @return a short-lived scope, also allowed after the observation has ended
     */
    public Scope makeCurrent() { return context.makeCurrent(); }

    /**
     * Records input subject to the capture policy, disabled by default.
     * @param input text; null is ignored
     * @return this observation
     */
    public LangfuseObservation input(String input) {
        return content(ContentCaptureType.INPUT, LangfuseAttributes.OBSERVATION_INPUT, input);
    }

    /**
     * Records output subject to the capture policy, disabled by default.
     * @param output text; null is ignored
     * @return this observation
     */
    public LangfuseObservation output(String output) {
        return content(ContentCaptureType.OUTPUT, LangfuseAttributes.OBSERVATION_OUTPUT, output);
    }

    private LangfuseObservation content(ContentCaptureType type, String attribute, String value) {
        if (ended) return this;
        String captured = contentPolicy.captureForObservation(type, value);
        if (captured != null) mutate(() -> span.setAttribute(attribute, captured));
        return this;
    }

    /**
     * Records explicitly supplied observation metadata. Metadata is not automatically redacted.
     * @param key nonblank metadata key
     * @param value metadata value
     * @return this observation
     * @throws IllegalArgumentException if the key is blank while the observation is open
     * @throws NullPointerException if key or value is null while the observation is open
     */
    public LangfuseObservation metadata(String key, String value) {
        if (ended) return this;
        requireText(key, "key");
        Objects.requireNonNull(value, "value");
        mutate(() -> span.setAttribute(LangfuseAttributes.OBSERVATION_METADATA + "." + key, value));
        return this;
    }

    /**
     * Records the model name using the existing Langfuse and GenAI attribute names.
     * @param name nonblank model name
     * @return this observation
     * @throws IllegalArgumentException if the name is blank while the observation is open
     * @throws NullPointerException if the name is null while the observation is open
     */
    public LangfuseObservation model(String name) {
        if (ended) return this;
        requireText(name, "name");
        mutate(() -> {
            span.setAttribute(LangfuseAttributes.OBSERVATION_MODEL, name);
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, name);
        });
        return this;
    }

    /**
     * Records nonnegative input/output token counts and their total as scalar attributes.
     * Omit this call for unknown usage; zero is a supplied value. SDK attribute limits still apply.
     * @param input input tokens
     * @param output output tokens
     * @return this observation
     * @throws IllegalArgumentException if a count is negative or their sum exceeds Long.MAX_VALUE
     *         while the observation is open
     */
    public LangfuseObservation usage(long input, long output) {
        if (ended) return this;
        if (input < 0 || output < 0 || input > Long.MAX_VALUE - output) {
            throw new IllegalArgumentException("Token counts must be nonnegative and their total must fit in a long");
        }
        mutate(() -> {
            span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, input);
            span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, output);
            span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, input + output);
        });
        return this;
    }

    private void mutate(Runnable mutation) {
        try {
            synchronized (lock) {
                if (!ended) mutation.run();
            }
        } catch (Throwable failure) {
            ObservationFailureSupport.rethrowIfFatal(failure);
        }
    }

    /** Ends normally if no other terminal operation has committed. */
    public void end() { finish(null, null, false); }

    /**
     * Records an error under the exception capture policy and ends, if still open after
     * details have been prepared. Passing an error does not rethrow that argument.
     * If preparing details itself raises a fatal error, termination is attempted before propagation.
     * @param failure the operation's failure
     * @throws NullPointerException if failure is null while the observation is open
     */
    public void fail(Throwable failure) {
        if (ended) return;
        Objects.requireNonNull(failure, "failure");
        String type = failure.getClass().getName();
        ExceptionRecorder.ExceptionDetails prepared;
        try {
            prepared = ExceptionRecorder.prepare(failure, exceptionPolicy, true);
        } catch (Throwable captureFailure) {
            try {
                finish(null, type, false);
            } finally {
                ObservationFailureSupport.rethrowIfFatal(captureFailure);
            }
            return;
        }
        finish(prepared, type, false);
    }

    /**
     * Ends with a "cancelled" status message without setting OTel ERROR. This does not
     * cancel any provider request, future, or child observation.
     */
    public void cancel() { finish(null, null, true); }

    private void finish(ExceptionRecorder.ExceptionDetails failure, String failureType, boolean cancelled) {
        boolean winner = false;
        try {
            synchronized (lock) {
                if (ended) return;
                ended = true;
                winner = true;
                if (failure != null) {
                    failure.applyTo(span);
                } else if (failureType != null) {
                    span.setStatus(StatusCode.ERROR);
                    span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
                    span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, failureType);
                } else if (cancelled) {
                    span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "cancelled");
                }
            }
        } catch (Throwable instrumentationFailure) {
            ObservationFailureSupport.rethrowIfFatal(instrumentationFailure);
        } finally {
            // A synchronous external processor may run arbitrary application code. Never hold lock here.
            if (winner) endSpan(span);
        }
    }

    private static void endSpan(Span span) {
        try { span.end(); }
        catch (Throwable failure) { ObservationFailureSupport.rethrowIfFatal(failure); }
    }

    /** Equivalent to {@link #end()}; does not close any scope or infer an exception. */
    @Override public void close() { end(); }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /**
     * Configures a new observation. Builders are not thread-safe; started observations are.
     * Each start creates an independent observation. No field-by-field context merge is performed.
     */
    public static final class Builder {
        private final Tracer tracer;
        private final ContentCapturePolicy contentPolicy;
        private final ExceptionCapturePolicy exceptionPolicy;
        private final String name;
        private final ObservationType type;
        private Context parent;
        private LangfuseTraceContext snapshot;

        Builder(Tracer tracer, ContentCapturePolicy contentPolicy, ExceptionCapturePolicy exceptionPolicy,
                String name, ObservationType type) {
            this.tracer = tracer;
            this.contentPolicy = contentPolicy;
            this.exceptionPolicy = exceptionPolicy;
            this.name = requireText(name, "name");
            this.type = Objects.requireNonNull(type, "type");
        }

        /**
         * Selects an explicit parent; worker-thread legacy values are not merged into it.
         * @param parent nonnull OTel context, including Context.root() for no parent
         * @return this builder
         * @throws NullPointerException if parent is null
         */
        public Builder parent(Context parent) {
            this.parent = Objects.requireNonNull(parent, "parent");
            return this;
        }

        /**
         * Selects a whole immutable snapshot in preference to the parent's metadata.
         * @param snapshot nonnull snapshot for this observation and descendants
         * @return this builder
         * @throws NullPointerException if snapshot is null
         */
        public Builder traceContext(LangfuseTraceContext snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            return this;
        }

        /**
         * Starts without making the observation current. If no parent was supplied, resolves
         * Context.current() now. If no snapshot was supplied, freezes metadata from that parent,
         * or uses an empty snapshot. Existing SDK resource defaults remain effective.
         * Nonfatal SDK setup failures produce a non-recording observation retaining the parent link.
         * @return a new observation
         */
        public LangfuseObservation start() {
            Context selectedParent = parent != null ? parent : Context.current();
            LangfuseTraceContext selectedSnapshot = snapshot != null ? snapshot : LangfuseContext.from(selectedParent);
            if (selectedSnapshot == null) selectedSnapshot = LangfuseTraceContext.builder().build();
            Context boundary = LangfuseContext.storeObservationSnapshot(selectedParent, selectedSnapshot);
            Span span = null;
            try {
                span = tracer.spanBuilder(name).setParent(boundary)
                        .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, type.attributeValue()).startSpan();
                LangfuseContext.applyTo(span, selectedSnapshot);
                return new LangfuseObservation(span, boundary.with(span), contentPolicy, exceptionPolicy);
            } catch (Throwable failure) {
                try { if (span != null) endSpan(span); }
                finally { ObservationFailureSupport.rethrowIfFatal(failure); }
                Span fallback = Span.wrap(Span.fromContext(selectedParent).getSpanContext());
                return new LangfuseObservation(fallback, boundary.with(fallback), contentPolicy, exceptionPolicy);
            }
        }
    }
}

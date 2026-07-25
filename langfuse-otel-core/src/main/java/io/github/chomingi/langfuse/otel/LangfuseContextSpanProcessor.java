package io.github.chomingi.langfuse.otel;

import io.opentelemetry.context.Context;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

class LangfuseContextSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        boolean currentParent = parentContext == Context.current();
        LangfuseTraceState traceState = LangfuseContext.traceStateFrom(parentContext);
        LangfuseTraceContext traceContext = traceState != null
                ? traceState.snapshot()
                : currentParent && LangfuseContext.hasLegacyOverride()
                        ? LangfuseContext.legacyCurrent()
                        : LangfuseContext.from(parentContext);
        if (traceContext == null && currentParent
                && Span.fromContext(parentContext).getSpanContext().isValid()) {
            traceContext = LangfuseContext.legacyCurrent();
        }
        LangfuseContext.applyTo(span, traceContext);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {}

    @Override
    public boolean isEndRequired() {
        return false;
    }
}

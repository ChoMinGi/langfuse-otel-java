package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import java.util.List;

class LangfuseContextSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String userId = LangfuseContext.getUserId();
        if (userId != null) span.setAttribute(LangfuseAttributes.TRACE_USER_ID, userId);

        String sessionId = LangfuseContext.getSessionId();
        if (sessionId != null) span.setAttribute(LangfuseAttributes.TRACE_SESSION_ID, sessionId);

        List<String> tags = LangfuseContext.getTags();
        if (!tags.isEmpty()) {
            span.setAttribute(AttributeKey.stringArrayKey(LangfuseAttributes.TRACE_TAGS), tags);
        }

        String environment = LangfuseContext.getEnvironment();
        if (environment != null) span.setAttribute(LangfuseAttributes.ENVIRONMENT, environment);
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

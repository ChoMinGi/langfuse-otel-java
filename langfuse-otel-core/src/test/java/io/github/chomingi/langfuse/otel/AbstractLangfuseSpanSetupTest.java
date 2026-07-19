package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractLangfuseSpanSetupTest {

    @AfterEach
    void clearContext() {
        LangfuseContext.clear();
    }

    @Test
    void spanIsEndedWhenMakingItCurrentFails() {
        AssertionError setupFailure = new AssertionError("scope setup failed");
        AtomicBoolean ended = new AtomicBoolean();
        Span span = (Span) Proxy.newProxyInstance(
                Span.class.getClassLoader(),
                new Class<?>[] {Span.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("makeCurrent")) {
                        throw setupFailure;
                    }
                    if (method.getName().equals("end")) {
                        ended.set(true);
                        return null;
                    }
                    return defaultValue(method.getReturnType(), proxy);
                });

        assertThatThrownBy(() -> new TestLangfuseSpan(span))
                .isSameAs(setupFailure);
        assertThat(ended).isTrue();
    }

    private static Object defaultValue(Class<?> returnType, Object proxy) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Double.TYPE) return 0D;
        if (returnType == Float.TYPE) return 0F;
        if (returnType.isInstance(proxy)) return proxy;
        return null;
    }

    private static final class TestLangfuseSpan extends AbstractLangfuseSpan {
        private TestLangfuseSpan(Span span) {
            super(span, "setup-test");
        }
    }
}

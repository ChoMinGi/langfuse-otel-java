package io.github.chomingi.langfuse.otel.spring;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanScopeSupportTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void delegateSpansBecomeChildrenAndCallingContextIsRestored() {
        Tracer tracer = otel.getOpenTelemetry().getTracer("scope-test");
        Span wrapper = tracer.spanBuilder("wrapper").startSpan();

        String result = SpanScopeSupport.call(wrapper, () -> {
            Span child = tracer.spanBuilder("delegate").startSpan();
            child.end();
            return "ok";
        });
        wrapper.end();

        assertThat(result).isEqualTo("ok");
        SpanData wrapperData = otel.getSpans().stream()
                .filter(span -> span.getName().equals("wrapper"))
                .findFirst().orElseThrow();
        SpanData childData = otel.getSpans().stream()
                .filter(span -> span.getName().equals("delegate"))
                .findFirst().orElseThrow();
        assertThat(childData.getParentSpanId()).isEqualTo(wrapperData.getSpanContext().getSpanId());
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(Context.current()).isEqualTo(Context.root());
    }

    @Test
    void delegateExceptionIsPreservedAndCallingContextIsRestored() {
        Span wrapper = otel.getOpenTelemetry().getTracer("scope-test")
                .spanBuilder("wrapper-error").startSpan();

        assertThatThrownBy(() -> SpanScopeSupport.call(wrapper, () -> {
            throw new IllegalStateException("delegate failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("delegate failed");

        wrapper.end();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void nonFatalMakeCurrentErrorDoesNotBlockTheDelegate() {
        AtomicBoolean invoked = new AtomicBoolean();
        Span span = spanWhoseMakeCurrentThrows(new AssertionError("scope setup failed"));

        String result = SpanScopeSupport.call(span, () -> {
            invoked.set(true);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(invoked).isTrue();
    }

    @Test
    void fatalMakeCurrentErrorIsRethrownBeforeTheDelegateRuns() {
        AtomicBoolean invoked = new AtomicBoolean();
        Span span = spanWhoseMakeCurrentThrows(new LinkageError("scope linkage failed"));

        assertThatThrownBy(() -> SpanScopeSupport.call(span, () -> {
            invoked.set(true);
            return "unreachable";
        })).isInstanceOf(LinkageError.class).hasMessage("scope linkage failed");
        assertThat(invoked).isFalse();
    }

    @Test
    void closeErrorDoesNotReplaceDelegateResultOrFailure() {
        Span span = spanWithScope(() -> {
            throw new AssertionError("scope close failed");
        });

        assertThat(SpanScopeSupport.call(span, () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> SpanScopeSupport.call(span, () -> {
            throw new IllegalStateException("delegate failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("delegate failed");
    }

    @Test
    void fatalCloseErrorIsRethrown() {
        Span span = spanWithScope(() -> {
            throw new LinkageError("scope close linkage failed");
        });

        assertThatThrownBy(() -> SpanScopeSupport.call(span, () -> "ok"))
                .isInstanceOf(LinkageError.class)
                .hasMessage("scope close linkage failed");
    }

    @Test
    void quietSpanCleanupSuppressesOnlyNonFatalFailures() {
        Span nonFatal = spanWhoseEndThrows(new AssertionError("nonfatal cleanup failure"));
        Span fatal = spanWhoseEndThrows(new LinkageError("fatal cleanup failure"));

        assertThatCode(() -> InstrumentationFailureSupport.endQuietly(nonFatal))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> InstrumentationFailureSupport.endQuietly(fatal))
                .isInstanceOf(LinkageError.class)
                .hasMessage("fatal cleanup failure");
    }

    private static Span spanWhoseMakeCurrentThrows(Throwable failure) {
        return (Span) Proxy.newProxyInstance(
                Span.class.getClassLoader(),
                new Class<?>[]{Span.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("makeCurrent")) throw failure;
                    return null;
                });
    }

    private static Span spanWithScope(Scope scope) {
        return (Span) Proxy.newProxyInstance(
                Span.class.getClassLoader(),
                new Class<?>[]{Span.class},
                (proxy, method, args) -> method.getName().equals("makeCurrent") ? scope : null);
    }

    private static Span spanWhoseEndThrows(Throwable failure) {
        return (Span) Proxy.newProxyInstance(
                Span.class.getClassLoader(),
                new Class<?>[]{Span.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("end")) throw failure;
                    return null;
                });
    }
}

package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Broken/custom SDK components must not change the terminal ownership or swallow fatal errors. */
class LangfuseObservationFailureTest {
    @Test void racingTerminalCallsInvokeTheUnderlyingEndOnlyOnceEvenWhenEndThrows() throws Exception {
        AtomicInteger ends = new AtomicInteger();
        Span span = span((name, args) -> {
            if (name.equals("end")) { ends.incrementAndGet(); throw new IllegalStateException("broken processor"); }
            return null;
        });
        LangfuseObservation observation = builder(span).start();
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> a = workers.submit(observation::end);
            Future<?> b = workers.submit(observation::cancel);
            Future<?> c = workers.submit(() -> observation.fail(new IllegalStateException()));
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS); c.get(5, TimeUnit.SECONDS);
            assertEquals(1, ends.get());
        } finally { workers.shutdownNow(); }
    }

    @Test void nonfatalAttributeAndTerminalFailuresAreContainedAndStillEnd() {
        AtomicInteger ends = new AtomicInteger();
        Span span = span((name, args) -> {
            if (name.equals("setAttribute") || name.equals("addEvent")) throw new AssertionError("SDK write");
            if (name.equals("end")) ends.incrementAndGet();
            return null;
        });
        LangfuseObservation observation = builder(span).start();
        assertDoesNotThrow(() -> observation.metadata("key", "value"));
        assertDoesNotThrow(() -> observation.fail(new RuntimeException("private")));
        observation.end(); assertEquals(1, ends.get());
    }

    @Test void setupFailureEndsPartiallyCreatedSpanAndFallbackPreservesParent() {
        AtomicInteger ends = new AtomicInteger();
        Span span = span((name, args) -> {
            if (name.equals("setAttribute")) throw new IllegalStateException("SDK setup");
            if (name.equals("end")) ends.incrementAndGet();
            return null;
        });
        SpanContext parent = SpanContext.createFromRemoteParent("12345678901234567890123456789012",
                "1234567890123456", TraceFlags.getSampled(), TraceState.getDefault());
        LangfuseObservation observation = builder(span).parent(Context.root().with(Span.wrap(parent)))
                .traceContext(LangfuseTraceContext.builder().userId("user").build()).start();
        assertEquals(parent, Span.fromContext(observation.context()).getSpanContext());
        assertFalse(Span.fromContext(observation.context()).isRecording());
        assertEquals(1, ends.get()); observation.close(); assertEquals(1, ends.get());
    }

    @Test void fatalSdkFailuresPropagateAfterCleanupAndNeverTriggerASecondEnd() {
        for (Error fatal : new Error[]{new SyntheticVmError(), new ThreadDeath(), new LinkageError("synthetic")}) {
            AtomicInteger ends = new AtomicInteger();
            Span span = span((name, args) -> {
                if (name.equals("setAttribute")) throw fatal;
                if (name.equals("end")) ends.incrementAndGet();
                return null;
            });
            assertSame(fatal, assertThrows(fatal.getClass(), () -> builder(span)
                    .traceContext(LangfuseTraceContext.builder().userId("user").build()).start()));
            assertEquals(1, ends.get());
            LangfuseObservation observation = builder(span).start();
            assertSame(fatal, assertThrows(fatal.getClass(), () -> observation.model("model")));
            assertSame(fatal, assertThrows(fatal.getClass(), observation::cancel));
            observation.end(); assertEquals(2, ends.get());
        }
    }

    @Test void fatalThrowableAccessDuringPreparationPropagatesAfterEnd() {
        AtomicInteger ends = new AtomicInteger();
        Span span = span((name, args) -> { if (name.equals("end")) ends.incrementAndGet(); return null; });
        LinkageError fatal = new LinkageError("synthetic");
        RuntimeException failure = new RuntimeException() {
            @Override public String getMessage() { throw fatal; }
        };
        LangfuseObservation observation = new LangfuseObservation.Builder(tracer(span), ContentCapturePolicy.metadataOnly(),
                ExceptionCapturePolicy.captureAll(), "operation", ObservationType.SPAN).start();
        assertSame(fatal, assertThrows(LinkageError.class, () -> observation.fail(failure)));
        observation.close(); assertEquals(1, ends.get());
    }

    private static LangfuseObservation.Builder builder(Span span) {
        return new LangfuseObservation.Builder(tracer(span), ContentCapturePolicy.metadataOnly(),
                ExceptionCapturePolicy.typeOnly(), "operation", ObservationType.SPAN);
    }
    private static Tracer tracer(Span span) {
        SpanBuilder builder = (SpanBuilder) Proxy.newProxyInstance(SpanBuilder.class.getClassLoader(),
                new Class<?>[]{SpanBuilder.class}, (proxy, method, args) -> method.getName().equals("startSpan") ? span : proxy);
        return (Tracer) Proxy.newProxyInstance(Tracer.class.getClassLoader(), new Class<?>[]{Tracer.class},
                (proxy, method, args) -> builder);
    }
    private static Span span(BiFunction<String, Object[], Object> action) {
        return (Span) Proxy.newProxyInstance(Span.class.getClassLoader(), new Class<?>[]{Span.class}, (proxy, method, args) -> {
            action.apply(method.getName(), args);
            if (method.getName().equals("storeInContext")) return ((Context) args[0]).with(Span.getInvalid());
            if (method.getName().equals("getSpanContext")) return SpanContext.getInvalid();
            if (method.getReturnType() == boolean.class) return false;
            return method.getReturnType() == void.class ? null : proxy;
        });
    }
    private static final class SyntheticVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}

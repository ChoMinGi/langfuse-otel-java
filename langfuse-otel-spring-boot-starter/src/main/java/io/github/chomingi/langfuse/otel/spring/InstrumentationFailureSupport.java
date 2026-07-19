package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;

/** Internal cleanup and fatal-error handling shared by automatic instrumentation wrappers. */
final class InstrumentationFailureSupport {

    private InstrumentationFailureSupport() {}

    static void endQuietly(LangfuseGeneration generation) {
        if (generation == null) return;
        try {
            generation.end();
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            // Nonfatal cleanup failures must not replace the application failure or result.
        }
    }

    static void endQuietly(Span span) {
        if (span == null) return;
        try {
            span.end();
        } catch (Throwable failure) {
            rethrowIfFatal(failure);
            // Nonfatal cleanup failures must not replace the application failure or result.
        }
    }

    static void recordExceptionQuietly(LangfuseOtel langfuseOtel, Span span, Throwable failure) {
        try {
            langfuseOtel.recordException(span, failure);
        } catch (Throwable recordingFailure) {
            rethrowIfFatal(recordingFailure);
            // Preserve the delegate failure when exception recording has a nonfatal failure.
        }
    }

    static void recordExceptionQuietly(LangfuseOtel langfuseOtel,
                                       LangfuseGeneration generation, Throwable failure) {
        try {
            langfuseOtel.recordException(generation, failure);
        } catch (Throwable recordingFailure) {
            rethrowIfFatal(recordingFailure);
            // Preserve the delegate failure when exception recording has a nonfatal failure.
        }
    }

    /** Rethrows only JVM-fatal errors after instrumentation resources have been cleaned up. */
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

    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }
}

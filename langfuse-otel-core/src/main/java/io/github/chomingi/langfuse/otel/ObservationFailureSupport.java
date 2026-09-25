package io.github.chomingi.langfuse.otel;

/** Fatal-error handling for the opt-in observation path. Legacy capture behavior is unchanged. */
final class ObservationFailureSupport {
    private ObservationFailureSupport() {}

    static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
        if (failure instanceof LinkageError) throw (LinkageError) failure;
    }
}

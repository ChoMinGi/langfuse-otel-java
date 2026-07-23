package io.github.chomingi.langfuse.otel;

/**
 * Immutable operational snapshot for a {@link LangfuseOtel} instance.
 *
 * <p>Exporter and queue signals are available only when {@link LangfuseOtel}
 * owns the standalone OpenTelemetry pipeline. External pipelines remain under
 * application control and are reported as not managed rather than as healthy
 * zero values. A successful flush describes local SDK drain completion only;
 * exporter state independently reports the latest delivery result.</p>
 */
public final class LangfuseOtelStatus {

    /** Explains why fail-safe construction returned a no-op instance. */
    public enum NoopReason {
        /** The instance is active and did not fall back to no-op mode. */
        NONE,
        /** Standalone credentials were absent. */
        MISSING_CREDENTIALS,
        /** Initialization failed while fail-safe construction was enabled. */
        INITIALIZATION_FAILURE
    }

    /** Describes the most recently completed standalone export. */
    public enum ExportState {
        /** Exporter state belongs to an external pipeline or no active pipeline exists. */
        NOT_MANAGED,
        /** No standalone export has completed yet. */
        NOT_ATTEMPTED,
        /** The most recently completed standalone exporter call reported success. */
        SUCCEEDED,
        /** The most recently completed standalone exporter call reported failure. */
        FAILED
    }

    /** Describes the most recent explicit {@link LangfuseOtel#flush()} operation. */
    public enum FlushState {
        /** Flush lifecycle belongs to an external pipeline or no active pipeline exists. */
        NOT_MANAGED,
        /** No explicit standalone flush has been requested. */
        NOT_REQUESTED,
        /** At least one standalone flush is currently waiting for completion. */
        IN_PROGRESS,
        /**
         * The most recent standalone SDK flush completed locally. This does not prove
         * that the remote endpoint accepted every span; consult {@link ExportState} separately.
         */
        SUCCEEDED,
        /** The flush returned a failed result or the wait was interrupted. */
        FAILED,
        /** The most recent standalone flush did not complete within the configured wait. */
        TIMED_OUT
    }

    private final LangfuseOtel.OpenTelemetryOwnership openTelemetryOwnership;
    private final boolean noopFallback;
    private final NoopReason noopReason;
    private final boolean operationalSignalsAvailable;
    private final ExportState exportState;
    private final long failedExportSpanCount;
    private final long queueDroppedSpanCount;
    private final boolean queueDropsSinceLastSuccessfulExport;
    private final FlushState flushState;
    private final long failedFlushCount;
    private final long timedOutFlushCount;

    LangfuseOtelStatus(LangfuseOtel.OpenTelemetryOwnership openTelemetryOwnership,
                       boolean noopFallback,
                       NoopReason noopReason,
                       boolean operationalSignalsAvailable,
                       ExportState exportState,
                       long failedExportSpanCount,
                       long queueDroppedSpanCount,
                       boolean queueDropsSinceLastSuccessfulExport,
                       FlushState flushState,
                       long failedFlushCount,
                       long timedOutFlushCount) {
        this.openTelemetryOwnership = openTelemetryOwnership;
        this.noopFallback = noopFallback;
        this.noopReason = noopReason;
        this.operationalSignalsAvailable = operationalSignalsAvailable;
        this.exportState = exportState;
        this.failedExportSpanCount = failedExportSpanCount;
        this.queueDroppedSpanCount = queueDroppedSpanCount;
        this.queueDropsSinceLastSuccessfulExport = queueDropsSinceLastSuccessfulExport;
        this.flushState = flushState;
        this.failedFlushCount = failedFlushCount;
        this.timedOutFlushCount = timedOutFlushCount;
    }

    /**
     * Returns who owns the OpenTelemetry lifecycle.
     *
     * @return the ownership mode
     */
    public LangfuseOtel.OpenTelemetryOwnership getOpenTelemetryOwnership() {
        return openTelemetryOwnership;
    }

    /**
     * Returns whether fail-safe construction produced a no-op instance.
     *
     * @return {@code true} for a fail-safe no-op
     */
    public boolean isNoopFallback() {
        return noopFallback;
    }

    /**
     * Returns why fail-safe construction produced a no-op instance.
     *
     * @return the no-op reason
     */
    public NoopReason getNoopReason() {
        return noopReason;
    }

    /**
     * Returns whether exporter, queue, and flush signals are managed by this instance.
     * External OpenTelemetry pipelines deliberately return {@code false}.
     *
     * @return {@code true} when operational signals are available
     */
    public boolean isOperationalSignalsAvailable() {
        return operationalSignalsAvailable;
    }

    /**
     * Returns the most recently completed standalone export state.
     *
     * @return the export state
     */
    public ExportState getExportState() {
        return exportState;
    }

    /**
     * Returns spans contained in standalone exporter calls reported as failed.
     * This counter is meaningful only when operational signals are available.
     *
     * @return cumulative spans in failed export calls
     */
    public long getFailedExportSpanCount() {
        return failedExportSpanCount;
    }

    /**
     * Returns the cumulative number of spans dropped when the standalone queue was full.
     * This counter is meaningful only when operational signals are available.
     *
     * @return cumulative queue-dropped spans
     */
    public long getQueueDroppedSpanCount() {
        return queueDroppedSpanCount;
    }

    /**
     * Returns whether a queue drop was observed since startup or the latest successful export.
     * A later success shows pipeline progress; it cannot recover spans that were already dropped.
     *
     * @return {@code true} when a queue drop has occurred since startup or the latest successful
     * export
     */
    public boolean hasQueueDropsSinceLastSuccessfulExport() {
        return queueDropsSinceLastSuccessfulExport;
    }

    /**
     * Returns the most recent explicit standalone flush state.
     * Success means local SDK drain completion, not guaranteed remote delivery.
     *
     * @return the flush state
     */
    public FlushState getFlushState() {
        return flushState;
    }

    /**
     * Returns explicit owned-pipeline flushes that failed or were interrupted.
     * External and no-op values are not observations of their pipelines.
     *
     * @return cumulative failed flushes
     */
    public long getFailedFlushCount() {
        return failedFlushCount;
    }

    /**
     * Returns explicit owned-pipeline flushes that exceeded the local wait timeout.
     * External and no-op values are not observations of their pipelines.
     *
     * @return cumulative timed-out flushes
     */
    public long getTimedOutFlushCount() {
        return timedOutFlushCount;
    }
}

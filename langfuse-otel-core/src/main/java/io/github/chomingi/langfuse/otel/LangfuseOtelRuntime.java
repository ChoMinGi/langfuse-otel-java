package io.github.chomingi.langfuse.otel;

import java.util.Objects;

import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.ExportState;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.FlushState;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.NoopReason;

final class LangfuseOtelRuntime {

    private final boolean operationalSignalsAvailable;
    private final boolean flushManaged;
    private final NoopReason noopReason;

    private ExportState exportState;
    private long failedExportSpanCount;
    private long queueDroppedSpanCount;
    private boolean queueDropsSinceLastSuccessfulExport;

    private FlushState lastFlushOutcome;
    private long flushSequence;
    private long latestFlushSequence;
    private long flushesInProgress;
    private long failedFlushCount;
    private long timedOutFlushCount;

    private LangfuseOtelRuntime(boolean operationalSignalsAvailable,
                                boolean flushManaged,
                                NoopReason noopReason) {
        this.operationalSignalsAvailable = operationalSignalsAvailable;
        this.flushManaged = flushManaged;
        this.noopReason = Objects.requireNonNull(noopReason, "noopReason");
        this.exportState = operationalSignalsAvailable
                ? ExportState.NOT_ATTEMPTED
                : ExportState.NOT_MANAGED;
        this.lastFlushOutcome = flushManaged
                ? FlushState.NOT_REQUESTED
                : FlushState.NOT_MANAGED;
    }

    static LangfuseOtelRuntime managed() {
        return new LangfuseOtelRuntime(true, true, NoopReason.NONE);
    }

    static LangfuseOtelRuntime unmonitored(boolean flushManaged, NoopReason noopReason) {
        return new LangfuseOtelRuntime(false, flushManaged, noopReason);
    }

    synchronized void recordExportCompleted(boolean success, long spanCount) {
        if (!operationalSignalsAvailable || spanCount <= 0) {
            return;
        }
        if (success) {
            exportState = ExportState.SUCCEEDED;
            queueDropsSinceLastSuccessfulExport = false;
        } else {
            failedExportSpanCount += spanCount;
            exportState = ExportState.FAILED;
        }
    }

    synchronized void recordQueueDropped(long spanCount) {
        if (!operationalSignalsAvailable || spanCount <= 0) {
            return;
        }
        queueDroppedSpanCount += spanCount;
        queueDropsSinceLastSuccessfulExport = true;
    }

    synchronized long beginFlush() {
        if (!flushManaged) {
            return -1L;
        }
        long sequence = ++flushSequence;
        latestFlushSequence = sequence;
        flushesInProgress++;
        return sequence;
    }

    synchronized void completeFlush(long sequence, FlushState outcome) {
        if (!flushManaged || sequence < 0) {
            return;
        }
        if (flushesInProgress > 0) {
            flushesInProgress--;
        }
        if (outcome == FlushState.TIMED_OUT) {
            timedOutFlushCount++;
        } else if (outcome == FlushState.FAILED) {
            failedFlushCount++;
        }
        if (sequence == latestFlushSequence) {
            lastFlushOutcome = outcome;
        }
    }

    LangfuseOtelStatus snapshot(LangfuseOtel.OpenTelemetryOwnership ownership,
                                boolean noopFallback) {
        synchronized (this) {
            FlushState visibleFlushState = flushesInProgress > 0
                    ? FlushState.IN_PROGRESS
                    : lastFlushOutcome;
            return new LangfuseOtelStatus(
                    ownership,
                    noopFallback,
                    noopReason,
                    operationalSignalsAvailable,
                    exportState,
                    failedExportSpanCount,
                    queueDroppedSpanCount,
                    queueDropsSinceLastSuccessfulExport,
                    visibleFlushState,
                    failedFlushCount,
                    timedOutFlushCount);
        }
    }
}

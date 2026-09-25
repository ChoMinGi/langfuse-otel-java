package operations;

import java.util.concurrent.TimeUnit;

/** Reject suspended/clock-discontinuous measurements instead of treating them as soak time. */
final class TimingGuard {
    static final long MAX_CLOCK_DIVERGENCE_MILLIS = 2000;
    private final long wallStart = System.currentTimeMillis();
    private final long monotonicStart = System.nanoTime();
    private long maxDivergenceMillis;

    void checkpoint() {
        long wallMillis = System.currentTimeMillis() - wallStart;
        long monotonicMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - monotonicStart);
        long divergence = Math.abs(wallMillis - monotonicMillis);
        maxDivergenceMillis = Math.max(maxDivergenceMillis, divergence);
        validate(wallMillis, monotonicMillis);
    }

    static void validate(long wallMillis, long monotonicMillis) {
        if (wallMillis < 0 || monotonicMillis < 0
                || Math.abs(wallMillis - monotonicMillis) > MAX_CLOCK_DIVERGENCE_MILLIS) {
            throw new IllegalStateException("Invalid measurement clocks: wallMs=" + wallMillis
                    + ", monotonicMs=" + monotonicMillis + "; rerun without suspension/clock changes");
        }
    }

    double wallElapsedSeconds() { return (System.currentTimeMillis() - wallStart) / 1000.0; }
    long maxDivergenceMillis() { return maxDivergenceMillis; }
}

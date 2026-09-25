package operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chomingi.langfuse.otel.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.lang.management.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in exploratory measurements, not JMH or a machine-independent performance budget. */
@EnabledIfSystemProperty(named = "operations.load", matches = "true")
class LoadTest {
    private static final String INPUT = "i".repeat(1024);
    private static final String OUTPUT = "o".repeat(1024);
    private static final LangfuseTraceContext SNAPSHOT = LangfuseTraceContext.builder()
            .userId("synthetic-user").sessionId("synthetic-session").tags("operations")
            .metadata("fixture", "local").build();
    private static final com.sun.management.ThreadMXBean ALLOCATION = allocationBean();
    private static volatile long blackhole;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final TimingGuard timing = new TimingGuard();

    @Test void measure() throws Exception {
        String profile = System.getProperty("operations.profile", "matrix");
        long started = System.nanoTime();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("startedAt", Instant.now().toString());
        environment.put("java", System.getProperty("java.runtime.version"));
        environment.put("vm", System.getProperty("java.vm.name"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("arch", System.getProperty("os.arch"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        environment.put("vmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        environment.put("profile", profile);
        environment.put("allocationSupported", ALLOCATION != null);
        environment.put("clockDivergenceLimitMillis", TimingGuard.MAX_CLOCK_DIVERGENCE_MILLIS);
        int concurrency = Integer.getInteger("operations.concurrency", 32);
        if (concurrency < 1 || concurrency > 128) throw new IllegalArgumentException("concurrency must be 1..128");
        int poolSize = profile.equals("matrix") ? 128 : concurrency;
        environment.put("prestartedWorkerThreads", poolSize);
        Map<String, String> classHashes = new TreeMap<>();
        for (Class<?> type : new Class<?>[]{LoadTest.class, TimingGuard.class, LocalOtlpReceiver.class,
                LangfuseObservation.class, LangfuseOtel.class}) {
            try (java.io.InputStream bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                classHashes.put(type.getName(), sha256(Objects.requireNonNull(bytes).readAllBytes()));
            }
        }
        environment.put("executedClassSha256", classHashes);
        Path coreArtifact = Paths.get(LangfuseOtel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(coreArtifact)) environment.put("coreArtifactSha256", sha256(Files.readAllBytes(coreArtifact)));
        Path output = Paths.get(System.getProperty("operations.output", "target/operations/load-" + profile + ".json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (LocalOtlpReceiver receiver = new LocalOtlpReceiver(false);
             LangfuseOtel metadata = receiver.client(false);
             LangfuseOtel body = receiver.client(true)) {
            AtomicInteger workerSequence = new AtomicInteger();
            ThreadPoolExecutor workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize,
                    task -> new Thread(task, "operations-worker-" + workerSequence.incrementAndGet()));
            workers.prestartAllCoreThreads();
            try {
                save(output, environment, started, false);
                if (profile.equals("matrix")) {
                    int warmup = Integer.getInteger("operations.warmupSeconds", 2);
                    int duration = Integer.getInteger("operations.measureSeconds", 5);
                    for (int matrixConcurrency : new int[]{1, 32, 128}) {
                        for (String recording : new String[]{"none", "metadata", "body"}) {
                            for (LocalOtlpReceiver.Mode mode : new LocalOtlpReceiver.Mode[]{
                                    LocalOtlpReceiver.Mode.NORMAL, LocalOtlpReceiver.Mode.SLOW,
                                    LocalOtlpReceiver.Mode.UNAVAILABLE}) {
                                LangfuseOtel client = recording.equals("none") ? null : recording.equals("metadata") ? metadata : body;
                                run(workers, client, receiver, recording, mode, matrixConcurrency, warmup, false);
                                Map<String, Object> row = run(workers, client, receiver, recording, mode, matrixConcurrency, duration, true);
                                results.add(row);
                                save(output, environment, started, false);
                                System.out.println("LOAD " + json.writeValueAsString(row));
                            }
                        }
                    }
                } else if (profile.equals("soak")) {
                    int warmup = Integer.getInteger("operations.warmupSeconds", 600);
                    int cycles = Integer.getInteger("operations.cycles", 24);
                    int duration = Integer.getInteger("operations.measureSeconds", 300);
                    if (cycles < 1) throw new IllegalArgumentException("cycles must be positive");
                    Map<String, Object> warmupRow = run(workers, body, receiver, "body", LocalOtlpReceiver.Mode.NORMAL, concurrency, warmup, false);
                    environment.put("warmup", warmupRow);
                    save(output, environment, started, false);
                    for (int i = 0; i < cycles; i++) {
                        // First six cycles form the normal 30-minute baseline at defaults.
                        LocalOtlpReceiver.Mode mode = i < 6 ? LocalOtlpReceiver.Mode.NORMAL
                                : i % 3 == 0 ? LocalOtlpReceiver.Mode.UNAVAILABLE
                                : i % 3 == 1 ? LocalOtlpReceiver.Mode.SLOW : LocalOtlpReceiver.Mode.NORMAL;
                        Map<String, Object> row = run(workers, body, receiver, "body", mode, concurrency, duration, true);
                        row.put("cycle", i + 1);
                        results.add(row);
                        save(output, environment, started, false);
                        System.out.println("SOAK " + json.writeValueAsString(row));
                    }
                } else {
                    throw new IllegalArgumentException("unknown profile: " + profile);
                }
                assertEquals(0, receiver.invalidRequests.get());
                timing.checkpoint();
            } catch (Exception | AssertionError failure) {
                environment.put("failure", failure.toString());
                save(output, environment, started, false);
                throw failure;
            } finally {
                receiver.mode = LocalOtlpReceiver.Mode.NORMAL;
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                metadata.flush();
                body.flush();
            }
        }
        environment.put("finishedAt", Instant.now().toString());
        timing.checkpoint();
        save(output, environment, started, true);
    }

    private Map<String, Object> run(ExecutorService workers, LangfuseOtel client, LocalOtlpReceiver receiver,
                                  String recording, LocalOtlpReceiver.Mode mode, int concurrency,
                                  int seconds, boolean measured) throws Exception {
        if (seconds <= 0) throw new IllegalArgumentException("duration must be positive");
        timing.checkpoint();
        long gcCountBefore = gcCount();
        long gcMillisBefore = gcMillis();
        receiver.mode = mode;
        long acceptedBefore = receiver.accepted.get();
        long requestsBefore = receiver.requests.get();
        long failedBefore = client == null ? 0 : client.getStatus().getFailedExportSpanCount();
        long dropsBefore = client == null ? 0 : client.getStatus().getQueueDroppedSpanCount();
        int aggregateRate = Integer.getInteger("operations.rate", 3000);
        if (aggregateRate < 1) throw new IllegalArgumentException("rate must be positive");
        long interval = TimeUnit.SECONDS.toNanos(1) * concurrency / aggregateRate;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        long[] deadline = new long[1];
        List<Future<WorkerResult>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(workers.submit(() -> {
                Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
                Context original = Context.current();
                ready.countDown();
                go.await();
                long bytesBefore = allocated();
                long next = System.nanoTime();
                long checksum = 0;
                WeakReference<LangfuseObservation> sample = new WeakReference<>(null);
                while (System.nanoTime() < deadline[0]) {
                    long start = System.nanoTime();
                    if (client == null) {
                        checksum += INPUT.charAt(0) + OUTPUT.charAt(0);
                    } else {
                        LangfuseObservation observation = client.observation("load", ObservationType.GENERATION)
                                .traceContext(SNAPSHOT).start();
                        try (Scope ignored = observation.makeCurrent()) {
                            observation.input(INPUT).model("synthetic").metadata("kind", "load").usage(5, 7);
                            checksum += INPUT.charAt(0) + OUTPUT.charAt(0);
                            observation.output(OUTPUT);
                        } finally {
                            observation.end();
                        }
                        if (latency.getTotalCount() == 0) sample = new WeakReference<>(observation);
                    }
                    long elapsed = System.nanoTime() - start;
                    assertTrue(elapsed <= TimeUnit.SECONDS.toNanos(60), "operation exceeded histogram range");
                    latency.recordValue(elapsed);
                    next += interval;
                    long now = System.nanoTime();
                    if (next > now) LockSupport.parkNanos(next - now);
                    else next = now; // Do not issue catch-up bursts after a scheduler/GC stall.
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                }
                assertSame(original, Context.current(), "scope leaked on a worker");
                blackhole = checksum;
                long bytesAfter = allocated();
                return new WorkerResult(latency, bytesBefore < 0 ? -1 : bytesAfter - bytesBefore, sample);
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        long started = System.nanoTime();
        long wallStarted = System.currentTimeMillis();
        System.out.println("PHASE " + (measured ? "measure" : "warmup") + " " + recording
                + " " + mode + " concurrency=" + concurrency + " seconds=" + seconds);
        deadline[0] = started + TimeUnit.SECONDS.toNanos(seconds);
        go.countDown();
        Histogram latency = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
        long allocations = 0;
        List<WeakReference<LangfuseObservation>> samples = new ArrayList<>();
        long nextProgress = started + TimeUnit.SECONDS.toNanos(30);
        for (Future<WorkerResult> task : futures) {
            WorkerResult result;
            while (true) {
                try {
                    result = task.get(1, TimeUnit.SECONDS);
                    break;
                } catch (TimeoutException waiting) {
                    timing.checkpoint();
                    long now = System.nanoTime();
                    if (now > deadline[0] + TimeUnit.SECONDS.toNanos(30)) throw waiting;
                    if (now >= nextProgress) {
                        System.out.println("PROGRESS elapsedSeconds=" + (now - started) / 1_000_000_000L
                                + "/" + seconds + " recording=" + recording + " receiver=" + mode);
                        nextProgress = now + TimeUnit.SECONDS.toNanos(30);
                    }
                }
            }
            latency.add(result.latency);
            if (result.bytes >= 0) allocations += result.bytes;
            samples.add(result.sample);
        }
        double actualSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        double wallSeconds = (System.currentTimeMillis() - wallStarted) / 1000.0;
        timing.checkpoint();
        assertTrue(actualSeconds <= seconds + 5, "phase stalled; reject the performance sample");
        long phaseGcCount = gcCount() - gcCountBefore;
        long phaseGcMillis = gcMillis() - gcMillisBefore;
        // A flush has a bounded wait and may time out; account again after recovery/drain below.
        if (client != null) {
            client.flush();
            if (mode == LocalOtlpReceiver.Mode.UNAVAILABLE) {
                Thread.sleep(100); // Allow metric callback publication after BSP completion.
            }
        }
        long acceptedDuring = receiver.accepted.get() - acceptedBefore;
        long failed = client == null ? 0 : client.getStatus().getFailedExportSpanCount() - failedBefore;
        long dropped = client == null ? 0 : client.getStatus().getQueueDroppedSpanCount() - dropsBefore;
        receiver.mode = LocalOtlpReceiver.Mode.NORMAL;
        if (client != null) {
            client.flush();
            OwnedPipelineTest.await(() -> receiver.accepted.get() - acceptedBefore
                    + client.getStatus().getFailedExportSpanCount() - failedBefore
                    + client.getStatus().getQueueDroppedSpanCount() - dropsBefore == latency.getTotalCount(), 40);
        }
        // Do not retain completed Future result histograms across the idle/GC sample.
        futures.clear();
        ManagementFactory.getMemoryMXBean().gc();
        Thread.sleep(300);
        ManagementFactory.getMemoryMXBean().gc();
        Thread.sleep(300);
        long retainedSamples = samples.stream().filter(ref -> ref.get() != null).count();
        assertEquals(0, retainedSamples, "sampled ended observations retained after idle GC");
        timing.checkpoint();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recording", recording);
        row.put("receiver", mode.name());
        row.put("concurrency", concurrency);
        row.put("requestedSeconds", seconds);
        row.put("measuredSeconds", actualSeconds);
        row.put("wallMeasuredSeconds", wallSeconds);
        row.put("gcCollectionsDuringPhase", phaseGcCount);
        row.put("gcMillisDuringPhase", phaseGcMillis);
        row.put("targetOpsPerSecond", aggregateRate);
        row.put("operations", latency.getTotalCount());
        row.put("opsPerSecond", latency.getTotalCount() / actualSeconds);
        row.put("p50Nanos", latency.getValueAtPercentile(50));
        row.put("p95Nanos", latency.getValueAtPercentile(95));
        row.put("p99Nanos", latency.getValueAtPercentile(99));
        row.put("maxNanos", latency.getMaxValue());
        row.put("producerAllocatedBytesPerOp", ALLOCATION == null ? null : allocations / (double) latency.getTotalCount());
        row.put("acceptedBeforeRecovery", acceptedDuring);
        row.put("failedSpansBeforeRecovery", failed);
        row.put("queueDropsBeforeRecovery", dropped);
        row.put("acceptedIncludingDrain", receiver.accepted.get() - acceptedBefore);
        row.put("failedSpansIncludingDrain", client == null ? 0 : client.getStatus().getFailedExportSpanCount() - failedBefore);
        row.put("queueDropsIncludingDrain", client == null ? 0 : client.getStatus().getQueueDroppedSpanCount() - dropsBefore);
        row.put("httpRequestsIncludingDrain", receiver.requests.get() - requestsBefore);
        row.put("postGcLiveHeapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        row.put("threadsAfterIdle", ManagementFactory.getThreadMXBean().getThreadCount());
        row.put("batchWorkersAfterIdle", threadCount("BatchSpanProcessor"));
        row.put("loadWorkersAfterIdle", threadCount("operations-worker-"));
        row.put("sampledEndedObservations", client == null ? 0 : samples.size());
        row.put("retainedObservationSamples", retainedSamples);
        row.put("sampledAt", Instant.now().toString());
        if (measured && mode == LocalOtlpReceiver.Mode.NORMAL) {
            assertEquals(0, client == null ? 0 : client.getStatus().getFailedExportSpanCount() - failedBefore);
            assertEquals(0, client == null ? 0 : client.getStatus().getQueueDroppedSpanCount() - dropsBefore,
                    "normal receiver overloaded at configured offered rate");
        }
        assertEquals(((ThreadPoolExecutor) workers).getCorePoolSize(), threadCount("operations-worker-"));
        assertEquals(2, threadCount("BatchSpanProcessor"), "unexpected SDK worker accumulation");
        return row;
    }

    private void save(Path output, Map<String, Object> environment, long started, boolean complete) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("environment", environment);
        report.put("complete", complete);
        report.put("elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
        report.put("wallElapsedSeconds", timing.wallElapsedSeconds());
        report.put("maxClockDivergenceMillis", timing.maxDivergenceMillis());
        report.put("measurements", results);
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }
    private static long threadCount(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith(prefix)).count();
    }
    private static String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        StringBuilder hex = new StringBuilder();
        for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", part & 0xff));
        return hex.toString();
    }
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }
    private static com.sun.management.ThreadMXBean allocationBean() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) return null;
        com.sun.management.ThreadMXBean allocation = (com.sun.management.ThreadMXBean) bean;
        if (!allocation.isThreadAllocatedMemorySupported()) return null;
        allocation.setThreadAllocatedMemoryEnabled(true);
        return allocation;
    }
    private static long allocated() {
        return ALLOCATION == null ? -1 : ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    private static final class WorkerResult {
        final Histogram latency;
        final long bytes;
        final WeakReference<LangfuseObservation> sample;
        WorkerResult(Histogram latency, long bytes, WeakReference<LangfuseObservation> sample) {
            this.latency = latency; this.bytes = bytes; this.sample = sample;
        }
    }
}

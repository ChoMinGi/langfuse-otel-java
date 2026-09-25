package operations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chomingi.langfuse.otel.ContentCapturePolicy;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic loopback receiver. Load mode retains counters only, never spans or IDs. */
final class LocalOtlpReceiver implements AutoCloseable {
    enum Mode { NORMAL, SLOW, UNAVAILABLE, DISCONNECT, BLOCK }
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicLong requests = new AtomicLong();
    final AtomicLong accepted = new AtomicLong();
    final AtomicLong duplicates = new AtomicLong();
    final AtomicLong invalidRequests = new AtomicLong();
    private final Set<String> ids;
    volatile Mode mode = Mode.NORMAL;

    LocalOtlpReceiver(boolean keepIds) throws IOException {
        ids = keepIds ? ConcurrentHashMap.newKeySet() : null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(workers);
        server.createContext("/api/public/otel/v1/traces", this::receive);
        server.start();
    }

    LangfuseOtel client(boolean capture) {
        return LangfuseOtel.builder().host("http://127.0.0.1:" + server.getAddress().getPort())
                .publicKey("pk-local-synthetic").secretKey("sk-local-synthetic")
                .allowInsecureHttpForDevelopment(true).failSafe(false)
                .contentCapturePolicy(capture ? ContentCapturePolicy.captureAll() : ContentCapturePolicy.metadataOnly())
                .build();
    }

    private void receive(HttpExchange exchange) throws IOException {
        try {
            requests.incrementAndGet();
            Mode selected = mode;
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"4".equals(exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"))
                    || !"/api/public/otel/v1/traces".equals(exchange.getRequestURI().getPath())) {
                invalidRequests.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            // Fixture-only cap: body capture uses two 1 KiB values and <=512 spans/batch.
            byte[] body = exchange.getRequestBody().readNBytes(4 * 1024 * 1024 + 1);
            if (body.length > 4 * 1024 * 1024) throw new IOException("fixture request cap exceeded");
            entered.countDown();
            if (selected == Mode.BLOCK && !release.await(60, TimeUnit.SECONDS)) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (selected == Mode.SLOW) Thread.sleep(200);
            if (selected == Mode.DISCONNECT) return; // Read request, drop connection without a response.
            if (selected == Mode.UNAVAILABLE) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            ExportTraceServiceRequest payload = ExportTraceServiceRequest.parseFrom(body);
            payload.getResourceSpansList().forEach(resource -> resource.getScopeSpansList().forEach(scope ->
                    scope.getSpansList().forEach(span -> {
                        accepted.incrementAndGet();
                        if (ids != null) {
                            if (ids.size() >= 10000) throw new IllegalStateException("fixture ID cap exceeded");
                            String id = java.util.Base64.getEncoder().encodeToString(span.getTraceId().toByteArray())
                                    + ":" + java.util.Base64.getEncoder().encodeToString(span.getSpanId().toByteArray());
                            if (!ids.add(id)) duplicates.incrementAndGet();
                        }
                    })));
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1); // Empty protobuf ExportTraceServiceResponse.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @Override public void close() throws InterruptedException {
        release.countDown();
        server.stop(0);
        workers.shutdownNow();
        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("receiver threads remain");
    }
}

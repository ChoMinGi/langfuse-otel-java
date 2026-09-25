package preflight;

import com.fasterxml.jackson.databind.*;
import io.github.chomingi.langfuse.otel.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.chomingi.langfuse.otel.LangfuseAttributes.*;

/** Synthetic data only; requires the disposable 4.41.0 runner. No external model calls. */
@EnabledIfEnvironmentVariable(named = "PREFLIGHT_LIVE", matches = "true")
class LiveV4Test {
    final ObjectMapper json = new ObjectMapper();
    final String commit = env("PREFLIGHT_COMMIT");
    final String release = env("PREFLIGHT_RELEASE");
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test void readBackCaptureAndMetadataOnlyTraces() throws Exception { readBack(false); }
    @Test void readBackExplicitObservationApi() throws Exception { readBack(true); }

    void readBack(boolean observationApi) throws Exception {
        for (boolean capture : new boolean[] {true, false}) {
            String run = "preflight-" + UUID.randomUUID();
            LangfuseTraceContext snapshot = LangfuseTraceContext.builder().traceName(run)
                    .userId("synthetic-user").sessionId("synthetic-session").tags("preflight", "v4")
                    .environment("preflight").release(release).version(commit)
                    .metadata("run", run).build();
            String traceId;
            try (LangfuseOtel lf = LangfuseOtel.builder().host(env("LANGFUSE_HOST"))
                    .publicKey(env("LANGFUSE_PUBLIC_KEY")).secretKey(env("LANGFUSE_SECRET_KEY"))
                    .contentCapturePolicy(capture ? ContentCapturePolicy.captureAll() : ContentCapturePolicy.metadataOnly())
                    .failSafe(false).build()) {
                if (observationApi) {
                    traceId = emitObservations(lf, run, snapshot);
                } else {
                Context parent = LangfuseContext.storeIn(Context.root(), snapshot);
                Span root = RawOtelExamples.start(lf, run, "chain", parent, snapshot);
                traceId = root.getSpanContext().getTraceId();
                lf.recordInput(root, "synthetic-root-input");
                Context rootContext = parent.with(root);
                Span generation = RawOtelExamples.start(lf, "generation", "generation", rootContext, snapshot);
                generation.setAttribute(OBSERVATION_MODEL, "preflight-model");
                generation.setAttribute(OBSERVATION_USAGE_DETAILS, "{\"input\":5,\"output\":7,\"total\":12}");
                generation.setAttribute(OBSERVATION_METADATA + ".kind", "model");
                lf.recordInput(generation, "synthetic-prompt"); lf.recordOutput(generation, "synthetic-response");
                generation.end();
                Span tool = RawOtelExamples.start(lf, "tool", "tool", rootContext, snapshot);
                tool.setAttribute(OBSERVATION_METADATA + ".kind", "tool"); tool.end();
                lf.recordOutput(root, "synthetic-root-output"); root.end();
                }
                lf.flush();
            }
            JsonNode rows = awaitTrace(traceId);
            Files.createDirectories(Path.of("target"));
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target", (observationApi ? "live-observation-" : "live-v4-") + capture + ".json").toFile(), rows);
            assertEquals(3, rows.size());
            JsonNode root = row(rows, run), generation = row(rows, "generation"), tool = row(rows, "tool");
            assertEquals("CHAIN", root.path("type").asText());
            assertTrue(root.path("parentObservationId").isNull());
            assertEquals("GENERATION", generation.path("type").asText());
            assertEquals("TOOL", tool.path("type").asText());
            for (JsonNode child : new JsonNode[]{generation, tool}) {
                assertEquals(root.path("id").asText(), child.path("parentObservationId").asText());
                assertFalse(Instant.parse(child.path("startTime").asText()).isBefore(Instant.parse(root.path("startTime").asText())));
                assertFalse(Instant.parse(child.path("endTime").asText()).isAfter(Instant.parse(root.path("endTime").asText())));
            }
            for (JsonNode observation : rows) {
                assertEquals(traceId, observation.path("traceId").asText());
                assertEquals(run, observation.path("traceName").asText());
                assertEquals("synthetic-user", observation.path("userId").asText());
                assertEquals("synthetic-session", observation.path("sessionId").asText());
                assertEquals(run, observation.path("metadata").path("run").asText());
                assertEquals("preflight", observation.path("environment").asText());
                assertEquals(release, observation.path("release").asText());
                assertEquals(commit, observation.path("version").asText());
                assertEquals(json.readTree("[\"preflight\",\"v4\"]"), observation.path("tags"));
                assertFalse(Instant.parse(observation.path("endTime").asText()).isBefore(Instant.parse(observation.path("startTime").asText())));
                if (!capture) { assertTrue(observation.path("input").isNull()); assertTrue(observation.path("output").isNull()); }
            }
            assertEquals("model", generation.path("metadata").path("kind").asText());
            assertEquals("tool", tool.path("metadata").path("kind").asText());
            assertEquals("preflight-model", generation.path("model").asText(generation.path("providedModelName").asText()));
            assertEquals(5, generation.path("usageDetails").path("input").asInt());
            assertEquals(7, generation.path("usageDetails").path("output").asInt());
            assertEquals(12, generation.path("usageDetails").path("total").asInt());
            assertTrue(tool.path("usageDetails").isEmpty()); // Missing usage is not inferred as provider-reported zero.
            assertTrue(generation.path("timeToFirstToken").isNull()); // No invented first-token timestamp.
            String commonFilter = "{\"type\":\"stringObject\",\"column\":\"metadata\",\"key\":\"run\",\"operator\":\"=\",\"value\":\"" + run + "\"}";
            JsonNode filtered = query("filter=" + URLEncoder.encode("[" + commonFilter + "]", StandardCharsets.UTF_8));
            assertEquals(3, filtered.path("data").size(), "Common metadata is searchable on all observations");
            for (JsonNode found : filtered.path("data")) assertEquals(traceId, found.path("traceId").asText());
            JsonNode individual = query("filter=" + URLEncoder.encode("[" + commonFilter
                    + ",{\"type\":\"stringObject\",\"column\":\"metadata\",\"key\":\"kind\",\"operator\":\"=\",\"value\":\"tool\"}]", StandardCharsets.UTF_8));
            assertEquals(1, individual.path("data").size(), "Individual metadata is searchable");
            assertEquals(tool.path("id"), individual.path("data").get(0).path("id"));
            if (capture) {
                assertEquals("synthetic-root-input", root.path("input").asText());
                assertEquals("synthetic-root-output", root.path("output").asText());
                assertEquals("synthetic-prompt", generation.path("input").asText());
                assertEquals("synthetic-response", generation.path("output").asText());
            }
            Files.createDirectories(Path.of("target"));
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target", (observationApi ? "live-observation-" : "live-v4-") + capture + ".json").toFile(), rows);
            System.out.println("Verified Langfuse 4.41.0 trace " + traceId + " capture=" + capture + " observations=3 api=" + (observationApi ? "observation" : "raw"));
        }
    }
    String emitObservations(LangfuseOtel lf, String run, LangfuseTraceContext snapshot) {
        try (LangfuseObservation root = lf.observation(run, ObservationType.CHAIN)
                .parent(Context.root()).traceContext(snapshot).start()) {
            root.input("synthetic-root-input");
            try (LangfuseObservation generation = lf.observation("generation", ObservationType.GENERATION)
                    .parent(root.context()).start()) {
                generation.model("preflight-model").usage(5, 7).metadata("kind", "model")
                        .input("synthetic-prompt").output("synthetic-response");
            }
            try (LangfuseObservation tool = lf.observation("tool", ObservationType.TOOL).parent(root.context()).start()) {
                tool.metadata("kind", "tool");
            }
            root.output("synthetic-root-output");
            return Span.fromContext(root.context()).getSpanContext().getTraceId();
        }
    }
    JsonNode awaitTrace(String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonNode last = null;
        do {
            last = query("traceId=" + traceId);
            if (last.path("data").size() == 3) {
                assertTrue(last.path("meta").path("cursor").isMissingNode() || last.path("meta").path("cursor").isNull());
                return last.path("data");
            }
            Thread.sleep(1000);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for three observations: " + last);
    }
    JsonNode query(String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(env("LANGFUSE_HOST")
                + "/api/public/v2/observations?" + query
                + "&fields=core,basic,io,metadata,model,usage,trace_context&limit=10"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((env("LANGFUSE_PUBLIC_KEY") + ":" + env("LANGFUSE_SECRET_KEY")).getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Observations API status");
        return json.readTree(response.body());
    }
    JsonNode row(JsonNode rows, String name) {
        for (JsonNode row : rows) if (row.path("name").asText().equals(name)) return row;
        throw new AssertionError("Missing observation " + name);
    }
    static String env(String name) { return Objects.requireNonNull(System.getenv(name), name); }
}

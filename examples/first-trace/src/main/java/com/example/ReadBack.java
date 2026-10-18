package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/** Demo verification, separate from the tracing code. Never prints credentials or response bodies. */
final class ReadBack {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReadBack() {}

    static void verify(String host, String publicKey, String secretKey, String traceId, String run) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(host + "/api/public/v2/observations?traceId="
                + traceId + "&fields=core,basic,io,metadata,model,usage,trace_context&limit=10"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        int count = 0;
        do {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Read-back HTTP " + status
                        + ": check project API keys and LANGFUSE_HOST region. Do not share your secret key.");
            }
            // Retry transient server failures within the same bounded ingestion window.
            if (status != 429 && status < 500) {
                require(status == 200, "Read-back HTTP " + status + ": expected Observations API v2 over HTTPS.");
                JsonNode body = JSON.readTree(response.body());
                JsonNode rows = body.path("data");
                require(rows.isArray(), "Read-back response is missing an observation array.");
                count = rows.size();
                JsonNode cursor = body.path("meta").path("cursor");
                require(count <= 3 && (cursor.isNull() || cursor.isMissingNode()),
                        "Unexpected duplicate or additional observations.");
                if (count == 3) {
                    validate(rows, traceId, run);
                    Path evidence = Path.of(System.getProperty("first.trace.evidence", "target/first-trace.json"));
                    Files.createDirectories(evidence.toAbsolutePath().getParent());
                    JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), rows);
                    System.out.println("Verified 3 observations: CHAIN -> TOOL + GENERATION");
                    System.out.println("Verified parent links, synthetic input/output, and shared metadata.");
                    System.out.println("Open: " + host + "/project/" + row(rows, "java-first-trace").path("projectId").asText()
                            + "/traces/" + traceId);
                    System.out.println("Read-back evidence: " + evidence);
                    return;
                }
            }
            Thread.sleep(1000);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Ingestion was not verified: received " + count
                + "/3 observations. Check the printed trace ID, region, and exporter logs; flush is not an ingestion receipt.");
    }

    static void validate(JsonNode rows, String traceId, String run) {
        require(rows.isArray() && rows.size() == 3, "Expected exactly three observations.");
        JsonNode root = row(rows, "java-first-trace");
        JsonNode tool = row(rows, "lookup-docs");
        JsonNode generation = row(rows, "answer-question");
        require("CHAIN".equals(root.path("type").asText()) && root.path("parentObservationId").isNull(), "Invalid root.");
        require("TOOL".equals(tool.path("type").asText()), "Missing tool type.");
        require("GENERATION".equals(generation.path("type").asText()), "Missing generation type.");
        require(!root.path("id").asText().isEmpty() && !root.path("projectId").asText().isEmpty(), "Missing root IDs.");
        for (JsonNode child : new JsonNode[] {tool, generation}) {
            require(root.path("id").equals(child.path("parentObservationId")), "Incorrect parent link.");
        }
        for (JsonNode observation : rows) {
            require(traceId.equals(observation.path("traceId").asText()), "Incorrect trace ID.");
            require(run.equals(observation.path("metadata").path("run").asText()), "Missing run metadata.");
            require("synthetic-no-model-call".equals(observation.path("metadata").path("demo").asText()), "Missing fixture label.");
            require("java-first-trace".equals(observation.path("traceName").asText()), "Missing shared trace name.");
            require(observation.path("endTime").isTextual(), "Observation has not ended.");
        }
        require(FirstTrace.QUESTION.equals(root.path("input").asText()), "Root input was not ingested.");
        require(FirstTrace.ANSWER.equals(root.path("output").asText()), "Root output was not ingested.");
        require(FirstTrace.CONTEXT.equals(tool.path("output").asText()), "Tool output was not ingested.");
        require("langfuse-otel-java getting started".equals(tool.path("input").asText()), "Tool input was not ingested.");
        require((FirstTrace.QUESTION + "\nContext: " + FirstTrace.CONTEXT).equals(generation.path("input").asText()),
                "Generation input was not ingested.");
        require(FirstTrace.ANSWER.equals(generation.path("output").asText()), "Generation output was not ingested.");
        require("demo-fixture".equals(generation.path("model").asText()), "Unexpected model label.");
        require(generation.path("usageDetails").isEmpty(), "Fixture must not claim provider usage.");
    }

    private static JsonNode row(JsonNode rows, String name) {
        JsonNode found = null;
        for (JsonNode row : rows) {
            if (name.equals(row.path("name").asText())) {
                require(found == null, "Duplicate observation: " + name);
                found = row;
            }
        }
        require(found != null, "Missing observation: " + name);
        return found;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}

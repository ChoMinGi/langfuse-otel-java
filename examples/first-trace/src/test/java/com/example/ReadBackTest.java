package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadBackTest {
    @Test void acceptsCompleteTrace() {
        assertDoesNotThrow(() -> ReadBack.validate(trace(), "trace-id", "run-id"));
    }

    @Test void rejectsThreeUnrelatedObservations() {
        ArrayNode rows = trace();
        ((ObjectNode) rows.get(1)).put("parentObservationId", "unrelated-root");
        assertThrows(IllegalStateException.class, () -> ReadBack.validate(rows, "trace-id", "run-id"));
    }

    @Test void rejectsMissingContentAndUnfinishedObservations() {
        ArrayNode missingContent = trace();
        ((ObjectNode) missingContent.get(2)).remove("output");
        assertThrows(IllegalStateException.class, () -> ReadBack.validate(missingContent, "trace-id", "run-id"));
        ArrayNode unfinished = trace();
        ((ObjectNode) unfinished.get(0)).putNull("endTime");
        assertThrows(IllegalStateException.class, () -> ReadBack.validate(unfinished, "trace-id", "run-id"));
    }

    @Test void rejectsDuplicateNamesAndForeignRun() {
        ArrayNode duplicate = trace();
        duplicate.set(1, duplicate.get(2).deepCopy());
        assertThrows(IllegalStateException.class, () -> ReadBack.validate(duplicate, "trace-id", "run-id"));
        assertThrows(IllegalStateException.class, () -> ReadBack.validate(trace(), "trace-id", "other-run"));
    }

    @Test void credentialsAreOnlySentToAnExplicitHttpsOrigin() {
        assertDoesNotThrow(() -> FirstTrace.validateHost("https://cloud.langfuse.com"));
        for (String invalid : new String[] {"http://localhost:3000", "https://user:pass@example.com",
                "https://example.com/api", "https://example.com?key=secret", "https://example.com#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> FirstTrace.validateHost(invalid));
        }
    }

    private ArrayNode trace() {
        ArrayNode rows = new ObjectMapper().createArrayNode();
        String[] names = {"java-first-trace", "lookup-docs", "answer-question"};
        String[] types = {"CHAIN", "TOOL", "GENERATION"};
        for (int i = 0; i < 3; i++) {
            ObjectNode row = rows.addObject().put("id", "span-" + i).put("projectId", "project-id")
                    .put("traceId", "trace-id").put("traceName", "java-first-trace")
                    .put("name", names[i]).put("type", types[i]).put("endTime", "2026-10-18T00:00:00Z");
            if (i == 0) row.putNull("parentObservationId");
            else row.put("parentObservationId", "span-0");
            row.putObject("metadata").put("run", "run-id").put("demo", "synthetic-no-model-call");
            row.putObject("usageDetails");
        }
        ((ObjectNode) rows.get(0)).put("input", FirstTrace.QUESTION).put("output", FirstTrace.ANSWER);
        ((ObjectNode) rows.get(1)).put("input", "langfuse-otel-java getting started").put("output", FirstTrace.CONTEXT);
        ((ObjectNode) rows.get(2)).put("input", FirstTrace.QUESTION + "\nContext: " + FirstTrace.CONTEXT)
                .put("output", FirstTrace.ANSWER).put("model", "demo-fixture");
        return rows;
    }
}

package com.example;

import io.github.chomingi.langfuse.otel.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Application-owned execution; the core only owns observation state. */
public final class ObservationExample {
    private ObservationExample() {}

    public static String sync(LangfuseOtel langfuse, Context parent, String prompt, Supplier<String> model) {
        try (LangfuseObservation observation = langfuse.observation("chat", ObservationType.GENERATION).parent(parent).start();
             Scope scope = observation.makeCurrent()) {
            try {
                observation.input(prompt);
                String response = model.get();
                observation.output(response);
                return response;
            } catch (RuntimeException | Error failure) {
                observation.fail(failure);
                throw failure;
            }
        }
    }

    public static CompletionStage<String> async(LangfuseOtel langfuse, Context parent, String prompt,
                                                Supplier<CompletionStage<String>> model) {
        LangfuseObservation observation = langfuse.observation("chat-async", ObservationType.GENERATION).parent(parent).start();
        try {
            observation.input(prompt);
            CompletionStage<String> stage;
            // The scope covers submission only. The provider must propagate to its own executors.
            try (Scope scope = observation.makeCurrent()) { stage = model.get(); }
            stage.whenComplete((response, failure) -> {
                Throwable cause = failure;
                while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                        && cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (cause instanceof CancellationException) observation.cancel();
                else if (cause != null) observation.fail(cause);
                else {
                    try { observation.output(response); }
                    finally { observation.end(); }
                }
            });
            return stage;
        } catch (RuntimeException | Error failure) {
            observation.fail(failure);
            throw failure;
        }
    }
}

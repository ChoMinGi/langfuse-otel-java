package io.github.chomingi.langfuse.otel.spring.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an LLM generation to be traced in Langfuse.
 * Synchronous values, {@code CompletionStage} completion, and Reactor terminal signals are
 * observed without retaining a thread-affine OpenTelemetry scope across asynchronous work.
 *
 * <pre>{@code
 * @ObserveGeneration(name = "summarize", model = "gpt-4o", system = "openai")
 * public String summarize(String text) { return callLLM(text); }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ObserveGeneration {

    /** @return the span name, or an empty string to use the method name */
    String name() default "";

    /** @return the model identifier attached to the generation span */
    String model() default "";

    /** @return the model provider or system attached to the generation span */
    String system() default "";

    /** @return the generation operation name */
    String operation() default "chat";
}

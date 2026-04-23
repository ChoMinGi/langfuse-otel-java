package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

@Aspect
public class SpringAiInstrumentationAspect {

    private static final Logger log = LoggerFactory.getLogger(SpringAiInstrumentationAspect.class);

    private final LangfuseOtel langfuseOtel;

    public SpringAiInstrumentationAspect(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Around("execution(* org.springframework.ai.chat.model.ChatModel.call(..))")
    public Object interceptChatModelCall(ProceedingJoinPoint joinPoint) throws Throwable {
        LangfuseGeneration gen = null;
        try {
            String spanName = resolveSpanName(joinPoint);
            gen = new LangfuseGeneration(langfuseOtel.getTracer(), spanName);
            Object firstArg = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
            if (firstArg instanceof Prompt) {
                setRequestAttributes(gen, (Prompt) firstArg);
            } else if (firstArg instanceof String) {
                gen.input(firstArg);
            }
        } catch (Exception e) {
            log.debug("Langfuse instrumentation setup failed, proceeding without tracing", e);
        }

        try {
            Object result = joinPoint.proceed();

            try {
                if (result instanceof ChatResponse) {
                    setResponseAttributes(gen, (ChatResponse) result);
                } else if (result instanceof String && gen != null) {
                    gen.output(result);
                }
            } catch (Exception e) {
                log.debug("Failed to record response attributes", e);
            }

            return result;
        } catch (Throwable t) {
            if (gen != null) {
                try { gen.recordException(t); } catch (Exception ignored) {}
            }
            throw t;
        } finally {
            if (gen != null) {
                try { gen.end(); } catch (Exception ignored) {}
            }
        }
    }

    private String resolveSpanName(ProceedingJoinPoint joinPoint) {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        return className.replace("ChatModel", "").replace("ChatClient", "").toLowerCase() + ".chat";
    }

    private void setRequestAttributes(LangfuseGeneration gen, Prompt prompt) {
        if (gen == null) return;

        gen.system("spring-ai");

        ChatOptions options = prompt.getOptions();
        if (options != null) {
            if (options.getModel() != null) gen.model(options.getModel());
            if (options.getTemperature() != null) gen.temperature(options.getTemperature());
            if (options.getMaxTokens() != null) gen.maxTokens(options.getMaxTokens());
            if (options.getTopP() != null) gen.topP(options.getTopP());
        }

        try {
            StringBuilder inputBuilder = new StringBuilder("[");
            var messages = prompt.getInstructions();
            for (int i = 0; i < messages.size(); i++) {
                var msg = messages.get(i);
                if (i > 0) inputBuilder.append(",");
                inputBuilder.append("{\"role\":\"")
                        .append(msg.getMessageType().getValue())
                        .append("\",\"content\":\"")
                        .append(escapeJson(msg.getText()))
                        .append("\"}");
            }
            inputBuilder.append("]");
            gen.input(inputBuilder.toString());
        } catch (Exception e) {
            log.debug("Failed to extract input messages", e);
        }
    }

    private void setResponseAttributes(LangfuseGeneration gen, ChatResponse response) {
        if (gen == null || response == null) return;

        try {
            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata != null) {
                if (metadata.getModel() != null) {
                    gen.responseModel(metadata.getModel());
                    gen.model(metadata.getModel());
                }

                Usage usage = metadata.getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) gen.inputTokens(usage.getPromptTokens());
                    if (usage.getCompletionTokens() != null) gen.outputTokens(usage.getCompletionTokens());
                    if (usage.getTotalTokens() != null) gen.totalTokens(usage.getTotalTokens());
                }
            }

            if (response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null) gen.output(text);
            }
        } catch (Exception e) {
            log.debug("Failed to extract response attributes", e);
        }
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}

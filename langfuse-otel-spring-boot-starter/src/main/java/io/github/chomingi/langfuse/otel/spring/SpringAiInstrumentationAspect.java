package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.JsonUtils;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Arrays;
import java.util.List;

@Aspect
public class SpringAiInstrumentationAspect {

    private static final Logger log = LoggerFactory.getLogger(SpringAiInstrumentationAspect.class);

    private final LangfuseOtel langfuseOtel;

    public SpringAiInstrumentationAspect(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Around("execution(* org.springframework.ai.chat.model.ChatModel.call(..))")
    public Object interceptChatModelCall(ProceedingJoinPoint joinPoint) throws Throwable {
        Object firstArg = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
        if (shouldDeferToPromptCall(joinPoint, firstArg)) {
            return joinPoint.proceed();
        }

        LangfuseGeneration gen = null;
        try {
            String spanName = resolveSpanName(joinPoint);
            gen = new LangfuseGeneration(langfuseOtel.getTracer(), spanName);
            gen.system("spring-ai");
            if (firstArg instanceof Prompt) {
                setRequestAttributes(gen, joinPoint, (Prompt) firstArg);
            } else if (firstArg instanceof String) {
                applyDefaultOptions(gen, joinPoint);
                gen.input(toJsonMessages(List.of(new org.springframework.ai.chat.messages.UserMessage((String) firstArg))));
            } else if (firstArg instanceof Message[]) {
                applyDefaultOptions(gen, joinPoint);
                gen.input(toJsonMessages(Arrays.asList((Message[]) firstArg)));
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

    private boolean shouldDeferToPromptCall(ProceedingJoinPoint joinPoint, Object firstArg) {
        if (firstArg instanceof Prompt) {
            return false;
        }
        return ((MethodSignature) joinPoint.getSignature()).getMethod().isDefault();
    }

    private String resolveSpanName(ProceedingJoinPoint joinPoint) {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        return className.replace("ChatModel", "").replace("ChatClient", "").toLowerCase() + ".chat";
    }

    private void setRequestAttributes(LangfuseGeneration gen, ProceedingJoinPoint joinPoint, Prompt prompt) {
        if (gen == null) return;

        ChatOptions options = prompt.getOptions();
        if (options == null) {
            options = resolveDefaultOptions(joinPoint);
        }
        applyChatOptions(gen, options);

        try {
            gen.input(toJsonMessages(prompt.getInstructions()));
        } catch (Exception e) {
            log.debug("Failed to extract input messages", e);
        }
    }

    private void applyDefaultOptions(LangfuseGeneration gen, ProceedingJoinPoint joinPoint) {
        applyChatOptions(gen, resolveDefaultOptions(joinPoint));
    }

    private ChatOptions resolveDefaultOptions(ProceedingJoinPoint joinPoint) {
        Object target = joinPoint.getTarget();
        if (target instanceof ChatModel) {
            return ((ChatModel) target).getDefaultOptions();
        }
        return null;
    }

    private void applyChatOptions(LangfuseGeneration gen, ChatOptions options) {
        if (options != null) {
            if (options.getModel() != null) gen.model(options.getModel());
            if (options.getTemperature() != null) gen.temperature(options.getTemperature());
            if (options.getMaxTokens() != null) gen.maxTokens(options.getMaxTokens());
            if (options.getTopP() != null) gen.topP(options.getTopP());
        }
    }

    private String toJsonMessages(List<Message> messages) {
        StringBuilder inputBuilder = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i > 0) inputBuilder.append(",");
            inputBuilder.append("{\"role\":\"")
                    .append(msg.getMessageType().getValue())
                    .append("\",\"content\":\"")
                    .append(JsonUtils.escapeJson(msg.getText()))
                    .append("\"}");
        }
        inputBuilder.append("]");
        return inputBuilder.toString();
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

}

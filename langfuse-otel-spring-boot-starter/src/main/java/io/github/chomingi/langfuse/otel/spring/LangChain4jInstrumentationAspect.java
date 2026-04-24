package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.JsonUtils;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Aspect
public class LangChain4jInstrumentationAspect {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jInstrumentationAspect.class);

    private final LangfuseOtel langfuseOtel;

    public LangChain4jInstrumentationAspect(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Around("execution(* dev.langchain4j.model.chat.ChatModel.chat(..))")
    public Object interceptChatModelChat(ProceedingJoinPoint joinPoint) throws Throwable {
        Object firstArg = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
        if (shouldDeferToChatRequestCall(joinPoint, firstArg)) {
            return joinPoint.proceed();
        }

        LangfuseGeneration gen = null;
        try {
            String spanName = resolveSpanName(joinPoint);
            gen = new LangfuseGeneration(langfuseOtel.getTracer(), spanName);
            gen.system("langchain4j");
            setRequestAttributes(gen, joinPoint);
        } catch (Exception e) {
            log.debug("Langfuse instrumentation setup failed, proceeding without tracing", e);
        }

        try {
            Object response = joinPoint.proceed();

            try {
                if (response instanceof ChatResponse) {
                    setResponseAttributes(gen, (ChatResponse) response);
                } else if (response instanceof String && gen != null) {
                    gen.output(response);
                }
            } catch (Exception e) {
                log.debug("Failed to record response attributes", e);
            }

            return response;
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
        return className.replaceAll("ChatModel$|ChatLanguageModel$", "")
                .toLowerCase() + ".chat";
    }

    private boolean shouldDeferToChatRequestCall(ProceedingJoinPoint joinPoint, Object firstArg) {
        if (firstArg instanceof ChatRequest) {
            return false;
        }
        return ((MethodSignature) joinPoint.getSignature()).getMethod().isDefault();
    }

    private void setRequestAttributes(LangfuseGeneration gen, ProceedingJoinPoint joinPoint) {
        try {
            applyParameters(gen, resolveParameters(joinPoint));

            List<ChatMessage> messages = resolveMessages(joinPoint);
            if (messages != null && !messages.isEmpty()) {
                gen.input(toJsonMessages(messages));
            }
        } catch (Exception e) {
            log.debug("Failed to extract LangChain4j request attributes", e);
        }
    }

    private ChatRequestParameters resolveParameters(ProceedingJoinPoint joinPoint) {
        Object firstArg = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
        if (firstArg instanceof ChatRequest) {
            return ((ChatRequest) firstArg).parameters();
        }
        Object target = joinPoint.getTarget();
        if (target instanceof dev.langchain4j.model.chat.ChatModel) {
            return ((dev.langchain4j.model.chat.ChatModel) target).defaultRequestParameters();
        }
        return null;
    }

    private void applyParameters(LangfuseGeneration gen, ChatRequestParameters parameters) {
        if (parameters == null) return;
        if (parameters.modelName() != null) {
            gen.model(parameters.modelName());
        }
        if (parameters.temperature() != null) {
            gen.temperature(parameters.temperature());
        }
        if (parameters.maxOutputTokens() != null) {
            gen.maxTokens(parameters.maxOutputTokens());
        }
        if (parameters.topP() != null) {
            gen.topP(parameters.topP());
        }
    }

    @SuppressWarnings("unchecked")
    private List<ChatMessage> resolveMessages(ProceedingJoinPoint joinPoint) {
        Object firstArg = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0] : null;
        if (firstArg instanceof ChatRequest) {
            return ((ChatRequest) firstArg).messages();
        }
        if (firstArg instanceof String) {
            return Collections.singletonList(UserMessage.from((String) firstArg));
        }
        if (firstArg instanceof ChatMessage[]) {
            return Arrays.asList((ChatMessage[]) firstArg);
        }
        if (firstArg instanceof List<?>) {
            return (List<ChatMessage>) firstArg;
        }
        return Collections.emptyList();
    }

    private String toJsonMessages(List<ChatMessage> messages) {
        StringBuilder inputBuilder = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (i > 0) inputBuilder.append(",");
            inputBuilder.append("{\"role\":\"")
                    .append(msg.type().name().toLowerCase())
                    .append("\",\"content\":\"")
                    .append(JsonUtils.escapeJson(messageContent(msg)))
                    .append("\"}");
        }
        inputBuilder.append("]");
        return inputBuilder.toString();
    }

    private String messageContent(ChatMessage message) {
        if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            if (userMessage.hasSingleText()) {
                return userMessage.singleText();
            }
        }
        if (message instanceof SystemMessage) {
            return ((SystemMessage) message).text();
        }
        if (message instanceof AiMessage) {
            return ((AiMessage) message).text();
        }
        return String.valueOf(message);
    }

    private void setResponseAttributes(LangfuseGeneration gen, ChatResponse response) {
        if (gen == null || response == null) return;

        try {
            if (response.modelName() != null) {
                gen.responseModel(response.modelName());
                gen.model(response.modelName());
            }

            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                if (usage.inputTokenCount() != null) gen.inputTokens(usage.inputTokenCount());
                if (usage.outputTokenCount() != null) gen.outputTokens(usage.outputTokenCount());
                if (usage.totalTokenCount() != null) gen.totalTokens(usage.totalTokenCount());
            }

            if (response.aiMessage() != null && response.aiMessage().text() != null) {
                gen.output(response.aiMessage().text());
            }
        } catch (Exception e) {
            log.debug("Failed to extract LangChain4j response attributes", e);
        }
    }
}

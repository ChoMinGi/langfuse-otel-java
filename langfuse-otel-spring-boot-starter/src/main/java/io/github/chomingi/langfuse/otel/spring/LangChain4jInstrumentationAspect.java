package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Aspect
public class LangChain4jInstrumentationAspect {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jInstrumentationAspect.class);

    private final LangfuseOtel langfuseOtel;

    public LangChain4jInstrumentationAspect(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Around("execution(* dev.langchain4j.model.chat.ChatModel.chat(dev.langchain4j.model.chat.request.ChatRequest))")
    public Object interceptChatModelChat(ProceedingJoinPoint joinPoint) throws Throwable {
        ChatRequest request = (ChatRequest) joinPoint.getArgs()[0];
        String spanName = resolveSpanName(joinPoint);

        LangfuseGeneration gen = new LangfuseGeneration(langfuseOtel.getTracer(), spanName);

        try {
            gen.system("langchain4j");
            setRequestAttributes(gen, request);

            ChatResponse response = (ChatResponse) joinPoint.proceed();

            setResponseAttributes(gen, response);
            return response;

        } catch (Throwable t) {
            gen.recordException(t);
            throw t;
        } finally {
            gen.end();
        }
    }

    private String resolveSpanName(ProceedingJoinPoint joinPoint) {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        return className.replaceAll("ChatModel$|ChatLanguageModel$", "")
                .toLowerCase() + ".chat";
    }

    private void setRequestAttributes(LangfuseGeneration gen, ChatRequest request) {
        try {
            if (request.parameters() != null && request.parameters().modelName() != null) {
                gen.model(request.parameters().modelName());
            }
            if (request.parameters() != null && request.parameters().temperature() != null) {
                gen.temperature(request.parameters().temperature());
            }
            if (request.parameters() != null && request.parameters().maxOutputTokens() != null) {
                gen.maxTokens(request.parameters().maxOutputTokens());
            }
            if (request.parameters() != null && request.parameters().topP() != null) {
                gen.topP(request.parameters().topP());
            }

            List<ChatMessage> messages = request.messages();
            if (messages != null && !messages.isEmpty()) {
                StringBuilder inputBuilder = new StringBuilder("[");
                for (int i = 0; i < messages.size(); i++) {
                    ChatMessage msg = messages.get(i);
                    if (i > 0) inputBuilder.append(",");
                    inputBuilder.append("{\"role\":\"")
                            .append(msg.type().name().toLowerCase())
                            .append("\",\"content\":\"")
                            .append(escapeJson(msg.toString()))
                            .append("\"}");
                }
                inputBuilder.append("]");
                gen.input(inputBuilder.toString());
            }
        } catch (Exception e) {
            log.debug("Failed to extract LangChain4j request attributes", e);
        }
    }

    private void setResponseAttributes(LangfuseGeneration gen, ChatResponse response) {
        if (response == null) return;

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

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}

package com.example;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.data.message.UserMessage;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

@SpringBootApplication
public class LangChain4jExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(LangChain4jExampleApplication.class, args);
    }

    @Bean
    ChatModel chatModel(@Value("${openai.api-key}") String apiKey) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.7)
                .build();
    }

    @Bean
    CommandLineRunner run(ChatModel chatModel, AnnotatedService annotatedService) {
        return args -> {
            System.out.println("=== LangChain4j + Langfuse OTel 자동 계측 테스트 ===");

            System.out.println("\n[1] ChatModel.chat(ChatRequest) — AOP 자동 계측");
            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from("자바에서 AOP란 무엇인지 한 문장으로 답해줘"))
                    .build();
            ChatResponse response = chatModel.chat(request);
            System.out.println("  응답: " + response.aiMessage().text());
            System.out.println("  모델: " + response.modelName());
            System.out.println("  토큰: " + response.tokenUsage().totalTokenCount());

            System.out.println("\n[2] @ObserveGeneration 어노테이션 테스트");
            String annotatedResult = annotatedService.summarize("OpenTelemetry는 분산 추적 표준입니다.");
            System.out.println("  응답: " + annotatedResult);

            System.out.println("\n=== 완료! Langfuse 대시보드에서 확인하세요 ===");

            Thread.sleep(3000);
            System.exit(0);
        };
    }

    @Service
    static class AnnotatedService {

        private final ChatModel chatModel;

        AnnotatedService(ChatModel chatModel) {
            this.chatModel = chatModel;
        }

        @ObserveGeneration(name = "summarize-with-annotation", model = "gpt-4o-mini", system = "openai")
        public String summarize(String text) {
            return chatModel.chat("다음 문장을 한 줄로 요약해줘: " + text);
        }
    }
}

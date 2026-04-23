package com.example;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }

    @Bean
    CommandLineRunner run(ChatModel chatModel) {
        return args -> {
            System.out.println("=== Spring AI + Langfuse OTel 자동 계측 테스트 ===");

            System.out.println("\n[1] 간단한 문자열 호출");
            String result = chatModel.call("한국의 수도는?");
            System.out.println("  응답: " + result);

            System.out.println("\n[2] Prompt 객체 호출");
            ChatResponse response = chatModel.call(new Prompt("인사평가에서 MBO란 무엇인가요? 한 문장으로 답해주세요."));
            System.out.println("  응답: " + response.getResult().getOutput().getText());
            System.out.println("  모델: " + response.getMetadata().getModel());
            System.out.println("  토큰: " + response.getMetadata().getUsage().getTotalTokens());

            System.out.println("\n=== 완료! Langfuse 대시보드에서 Generation 확인하세요 ===");

            Thread.sleep(3000);
            System.exit(0);
        };
    }
}

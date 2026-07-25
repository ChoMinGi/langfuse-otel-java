package com.example.langchain4jconsumer;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LangChain4jConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LangChain4jConsumerApplication.class, args);
    }

    @Bean
    ChatModel chatModel() {
        return new StubLangChain4jChatModel();
    }
}

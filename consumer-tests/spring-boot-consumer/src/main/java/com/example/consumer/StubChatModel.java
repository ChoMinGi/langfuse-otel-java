package com.example.consumer;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

public class StubChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage("smoke response"))),
                ChatResponseMetadata.builder()
                        .model("smoke-model")
                        .build());
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder()
                .model("smoke-model")
                .build();
    }
}

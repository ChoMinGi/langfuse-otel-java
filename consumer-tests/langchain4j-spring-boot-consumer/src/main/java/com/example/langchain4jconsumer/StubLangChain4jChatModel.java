package com.example.langchain4jconsumer;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

public class StubLangChain4jChatModel implements ChatModel {

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        List<ChatMessage> messages = chatRequest.messages();
        ChatMessage lastMessage = messages.get(messages.size() - 1);
        String text = lastMessage instanceof UserMessage
                ? ((UserMessage) lastMessage).singleText()
                : String.valueOf(lastMessage);

        return ChatResponse.builder()
                .modelName("langchain4j-smoke-model")
                .tokenUsage(new TokenUsage(4, 5, 9))
                .aiMessage(AiMessage.from("langchain4j response: " + text))
                .build();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return DefaultChatRequestParameters.builder()
                .modelName("langchain4j-smoke-model")
                .build();
    }
}

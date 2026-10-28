package org.example.harness;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ChatService {

    private final OllamaChatModel chatModel;
    private final int maxHistoryTurns;
    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();

    public ChatService(
            OllamaChatModel chatModel,
            @Value("${app.chat.max-history-turns:20}") int maxHistoryTurns) {
        if (maxHistoryTurns < 1) {
            throw new IllegalArgumentException("app.chat.max-history-turns must be at least 1");
        }
        this.chatModel = chatModel;
        this.maxHistoryTurns = maxHistoryTurns;
    }

    public String reply(String conversationId, String userMessage) {
        Conversation conversation = conversations.computeIfAbsent(conversationId, ignored -> new Conversation());

        synchronized (conversation) {
            List<Message> promptMessages = new ArrayList<>(conversation.history);
            promptMessages.add(new UserMessage(userMessage));

            final String assistantMessage;
            try {
                OllamaChatOptions options = OllamaChatOptions.builder()
                        .model("qwen3.5:4b")
                        .numPredict(128)
                        .disableThinking()
                        .temperature(0.2)
                        .build();
                ChatResponse response = chatModel.call(new Prompt(promptMessages, options));
                assistantMessage = response.getResult().getOutput().getText();
                if (assistantMessage == null || assistantMessage.isBlank()) {
                    throw new IllegalStateException("Model returned an empty response");
                }
            } catch (RuntimeException exception) {
                throw new ChatModelException(exception);
            }

            conversation.history.addLast(new UserMessage(userMessage));
            conversation.history.addLast(new AssistantMessage(assistantMessage));
            trimHistory(conversation.history);

            return assistantMessage;
        }
    }

    private void trimHistory(ArrayDeque<Message> history) {
        int maxMessages = maxHistoryTurns * 2;
        while (history.size() > maxMessages) {
            history.removeFirst();
            history.removeFirst();
        }
    }

    private static final class Conversation {
        private final ArrayDeque<Message> history = new ArrayDeque<>();
    }
}

package org.example.harness;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ChatService {

    private static final DateTimeFormatter TIME_FORMAT =
                    DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm (VV, xxx)", Locale.ENGLISH);

    private final OllamaChatModel chatModel;
    private final ZoneId zone;
    private final int maxHistoryTurns;
    private final String model;
    private final int numPredict;
    private final double temperature;
    private final String systemPrompt;
    private final Cache<String, Conversation> conversations;

    public ChatService(
            OllamaChatModel chatModel,
            @Value("${app.chat.max-history-turns:20}") int maxHistoryTurns,
            @Value("${app.chat.max-conversations:1000}") long maxConversations,
            @Value("${app.chat.conversation-ttl-minutes:60}") long ttlMinutes,
            @Value("${spring.ai.ollama.chat.options.model:qwen3.5:4b}") String model,
            @Value("${spring.ai.ollama.chat.options.num-predict:512}") int numPredict,
            @Value("${spring.ai.ollama.chat.options.temperature:0.2}") double temperature,
            @Value("${app.chat.system-prompt:}") String systemPrompt,
            @Value("${app.chat.timezone:Europe/Moscow}") String timezone) {
        if (maxHistoryTurns < 1) {
            throw new IllegalArgumentException("app.chat.max-history-turns must be at least 1");
        }
        if (maxConversations < 1) {
            throw new IllegalArgumentException("app.chat.max-conversations must be at least 1");
        }
        if (ttlMinutes < 1) {
            throw new IllegalArgumentException("app.chat.conversation-ttl-minutes must be at least 1");
        }
        this.chatModel = chatModel;
        this.maxHistoryTurns = maxHistoryTurns;
        this.model = model;
        this.numPredict = numPredict;
        this.temperature = temperature;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt.strip();
        this.zone = ZoneId.of(timezone);
        this.conversations = Caffeine.newBuilder()
                .maximumSize(maxConversations)
                .expireAfterAccess(Duration.ofMinutes(ttlMinutes))
                .build();
    }

    public String reply(String conversationId, String userMessage) {
        Conversation conversation = conversations.get(conversationId, ignored -> new Conversation());

        synchronized (conversation) {
            List<Message> promptMessages = new ArrayList<>();
            promptMessages.add(new SystemMessage(buildSystemMessage()));
            promptMessages.addAll(conversation.history);
            promptMessages.add(new UserMessage(userMessage));

            final String assistantMessage;
            try {
                OllamaChatOptions options = OllamaChatOptions.builder()
                        .model(model)
                        .numPredict(numPredict)
                        .temperature(temperature)
                        .disableThinking()
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

    private String buildSystemMessage() {
        String timeInfo = "Current date and time: " + TIME_FORMAT.format(ZonedDateTime.now(zone))
                + ". Use it when the user asks about the date or time.";
        return systemPrompt.isEmpty() ? timeInfo : systemPrompt + "\n\n" + timeInfo;
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
package org.example.harness;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
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

    @Autowired
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
        this(chatModel, maxHistoryTurns, maxConversations, Duration.ofMinutes(ttlMinutes), model,
                numPredict, temperature, systemPrompt, timezone, Ticker.systemTicker());
    }

    ChatService(
            OllamaChatModel chatModel,
            int maxHistoryTurns,
            long maxConversations,
            Duration conversationTtl,
            String model,
            int numPredict,
            double temperature,
            String systemPrompt,
            String timezone,
            Ticker ticker) {
        if (maxHistoryTurns < 1) {
            throw new IllegalArgumentException("app.chat.max-history-turns must be at least 1");
        }
        if (maxConversations < 1) {
            throw new IllegalArgumentException("app.chat.max-conversations must be at least 1");
        }
        if (conversationTtl.isNegative() || conversationTtl.isZero()) {
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
                .expireAfterAccess(conversationTtl)
                .ticker(ticker)
                .build();
    }

    public Flux<String> stream(String conversationId, String userMessage) {
        return Flux.defer(() -> {
            Conversation conversation = conversations.get(conversationId, ignored -> new Conversation());

            conversation.lock.acquireUninterruptibly();
            AtomicReference<Usage> usage = new AtomicReference<>();
            Flux<String> chunks;
            try {
                chunks = chatModel.stream(buildPrompt(conversation, userMessage))
                        .doOnNext(chunk -> {

                            Usage chunkUsage = usageOf(chunk);
                            if (chunkUsage != null) {
                                usage.set(chunkUsage);
                            }
                        })
                        .map(ChatService::chunkText)
                        .filter(text -> !text.isBlank());
            } catch (RuntimeException exception) {
                conversation.lock.release();
                throw new ChatModelException(exception);
            }

            StringBuilder answer = new StringBuilder();
            return chunks
                    .doOnNext(answer::append)
                    .doOnComplete(() -> {
                        String assistantMessage = answer.toString();
                        if (assistantMessage.isBlank()) {
                            throw new IllegalStateException("Model returned an empty response");
                        }
                        logUsage(conversationId, usage.get(), conversation.history.size());
                        remember(conversation, userMessage, assistantMessage);
                    })
                    .onErrorMap(error -> error instanceof ChatModelException
                            ? error
                            : new ChatModelException(error))
                    .doFinally(signal -> conversation.lock.release());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private void logUsage(String conversationId, Usage usage, int historyMessages) {
        if (usage == null) {
            return;
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        log.info("Chat usage: conversation={}, promptTokens={}, completionTokens={}, "
                        + "historyMessages={}, numPredict={}",
                conversationId, promptTokens, completionTokens, historyMessages, numPredict);
        if (completionTokens != null && completionTokens >= numPredict) {
            log.warn("Reply for conversation={} reached num-predict={} and was probably cut off",
                    conversationId, numPredict);
        }
    }

    private Prompt buildPrompt(Conversation conversation, String userMessage) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(buildSystemPrompt()));
        promptMessages.addAll(conversation.history);
        promptMessages.add(new SystemMessage(buildTimeContext()));
        promptMessages.add(new UserMessage(userMessage));

        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(model)
                .numPredict(numPredict)
                .temperature(temperature)
                .disableThinking()
                .build();
        return new Prompt(promptMessages, options);
    }

    private void remember(Conversation conversation, String userMessage, String assistantMessage) {
        conversation.history.addLast(new UserMessage(userMessage));
        conversation.history.addLast(new AssistantMessage(assistantMessage));
        trimHistory(conversation.history);
    }

    private static String chunkText(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return "";
        }
        String text = chunk.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String buildSystemPrompt() {
        return systemPrompt;
    }

    private String buildTimeContext() {
        return "Current date and time: " + TIME_FORMAT.format(ZonedDateTime.now(zone))
                + ". Use it when the user asks about the date or time.";
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
        private final Semaphore lock = new Semaphore(1);
    }
}

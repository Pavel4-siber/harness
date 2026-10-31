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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service("chatService")
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm (VV, xxx)", Locale.ENGLISH);

    private final OllamaChatModel chatModel;
    private final ChatProperties props;
    private final ZoneId zone;
    private final Cache<String, Conversation> conversations;

    @Autowired
    public ChatService(OllamaChatModel chatModel, ChatProperties props) {
        if (props.getConversationTtl().isNegative() || props.getConversationTtl().isZero()) {
            throw new IllegalArgumentException("app.chat.conversation-ttl must be positive");
        }

        this.chatModel = chatModel;
        this.props = props;
        this.zone = ZoneId.of(props.getTimezone());

        this.conversations = Caffeine.newBuilder()
                .maximumSize(props.getMaxConversations())
                .expireAfterAccess(props.getConversationTtl())
                .ticker(Ticker.systemTicker())
                .removalListener((id, conversation, cause) -> {
                    Conversation conv = (Conversation) conversation;
                    if (conversation != null) {
                        conv.clearHistory();
                    }
                })
                .build();
    }

    public Flux<String> stream(String conversationId, String userMessage) {
        return Flux.defer(() -> {
                    Conversation conversation = conversations.get(conversationId, ignored -> new Conversation());

                    conversation.lock.acquireUninterruptibly(); // блокирование
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
                    Mono<String> fullAnswerMono = chunks.collect(Collectors.joining()); //потоковая обработка внутри сервиса, но не потоковая выдача ответа клиенту.

            return fullAnswerMono
                    .flatMap(assistantMessage -> {
                        logUsage(conversationId, usage.get(), conversation.history.size());
                        if (assistantMessage.isBlank())
                        {
                            assistantMessage = "Я пока не смог сформировать ответ.";
                        }
                        remember(conversation, userMessage, assistantMessage);
                        return Mono.just(assistantMessage); })
                    .flux()
                    .onErrorMap(error -> error instanceof ChatModelException ? error : new ChatModelException("Error streaming chat response", error))
                    .doFinally(signal -> conversation.lock.release()); //снятие блокировки
        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Prompt buildPrompt(Conversation conversation, String userMessage) {
        List<Message> promptMessages = new ArrayList<>();

        promptMessages.add(new SystemMessage(buildSystemPrompt()));
        promptMessages.addAll(conversation.history);
        promptMessages.add(new SystemMessage(buildTimeContext()));
        promptMessages.add(new UserMessage(userMessage));

        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(props.getDefaultModel())
                .numPredict(props.getDefaultNumPredict())
                .temperature(props.getDefaultTemperature())
                .disableThinking()
                .build();
        return new Prompt(promptMessages, options);
    }

    private void logUsage(String conversationId, Usage usage, int historyMessages) {
        if (usage == null) {
            return;
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();

        log.info("Chat usage: conversation={}, promptTokens={}, completionTokens={}, historyMessages={}, numPredict={}",
                conversationId, promptTokens, completionTokens, historyMessages, props.getDefaultNumPredict());

        if (completionTokens != null && completionTokens >= props.getDefaultNumPredict()) {
            log.warn("Reply for conversation={} reached num-predict={} and was probably cut off",
                    conversationId, props.getDefaultNumPredict());
        }
    }

    private void remember(Conversation conversation, String userMessage, String assistantMessage) {
        conversation.history.addLast(new UserMessage(userMessage));
        conversation.history.addLast(new AssistantMessage(assistantMessage));
        trimHistory(conversation.history);
    }

    private void trimHistory(ArrayDeque<Message> history) {
        int maxMessages = props.getMaxHistoryTurns() * 2;
        while (history.size() > maxMessages) {
            Message oldest = history.pollFirst();
            if (oldest instanceof UserMessage) {
                history.pollFirst();
            }
        }
    }

    private String buildSystemPrompt() {
        return props.getDefaultSystemPrompt();
    }

    private String buildTimeContext() {
        return "Current date and time: " + TIME_FORMAT.format(ZonedDateTime.now(zone))
                + ". Use it when the user asks about the date or time.";
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private static String chunkText(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return "";
        }
        String text = chunk.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static final class Conversation {
        private final ArrayDeque<Message> history = new ArrayDeque<>();
        private final Semaphore lock = new Semaphore(1);
        void clearHistory()
            { history.clear(); }
    }
}
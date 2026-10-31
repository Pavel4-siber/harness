
package org.example.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;

import org.springframework.ai.ollama.api.OllamaChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private OllamaChatModel chatModel;

    @Mock
    private ChatProperties props;

    private ChatService chatService;

    @BeforeEach
    void setUp() {

        lenient().when(props.getConversationTtl())
                .thenReturn(Duration.ofMinutes(60));

        lenient().when(props.getTimezone())
                .thenReturn("Europe/Moscow");

        lenient().when(props.getMaxConversations())
                .thenReturn(1000);

        lenient().when(props.getMaxHistoryTurns())
                .thenReturn(20);

        lenient().when(props.getDefaultModel())
                .thenReturn("qwen3.5:4b");

        lenient().when(props.getDefaultNumPredict())
                .thenReturn(512);

        lenient().when(props.getDefaultTemperature())
                .thenReturn(0.2);

        lenient().when(props.getDefaultSystemPrompt())
                .thenReturn("You are a helpful assistant.");

        chatService = new ChatService(chatModel, props);
    }

    @Test
    void shouldReturnAssistantResponse() {

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(createResponse("Привет!")));

        List<String> result = chatService
                .stream("conversation-1", "Привет")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Привет!", result.get(0));
        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void shouldRememberConversationHistory() {

        List<Prompt> prompts = new ArrayList<>();

        when(chatModel.stream(any(Prompt.class)))
                .thenAnswer(invocation -> {

                    Prompt prompt = invocation.getArgument(0);
                    prompts.add(prompt);

                    return Flux.just(createResponse("Ответ"));
                });

        // Первый запрос
        chatService.stream("conversation-1", "Первый вопрос")
                .blockLast(Duration.ofSeconds(5));

        // Второй запрос
        chatService.stream("conversation-1", "Второй вопрос")
                .blockLast(Duration.ofSeconds(5));

        assertEquals(2, prompts.size());

        List<Message> secondPromptMessages =
                prompts.get(1).getInstructions();

        // System + User + Assistant + TimeContext + User
        assertEquals(5, secondPromptMessages.size());

        assertInstanceOf(SystemMessage.class, secondPromptMessages.get(0));
        assertInstanceOf(UserMessage.class, secondPromptMessages.get(1));
        assertInstanceOf(AssistantMessage.class, secondPromptMessages.get(2));
        assertInstanceOf(SystemMessage.class, secondPromptMessages.get(3));
        assertInstanceOf(UserMessage.class, secondPromptMessages.get(4));

        assertEquals(
                "Первый вопрос",
                secondPromptMessages.get(1).getText()
        );
    }

    @Test
    void shouldReturnFallbackWhenResponseIsBlank() {

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(createResponse("")));

        String result = chatService
                .stream("conversation-1", "Привет")
                .blockFirst(Duration.ofSeconds(5));

        assertEquals(
                "Я пока не смог сформировать ответ.",
                result
        );
    }

    @Test
    void shouldWrapModelError() {

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(
                        new RuntimeException("Ollama connection failed")
                ));

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> chatService
                        .stream("conversation-1", "Привет")
                        .blockLast(Duration.ofSeconds(5))
        );
        assertNotNull(exception.getCause());
        assertEquals("Ollama connection failed", exception.getCause().getMessage());
    }

    @Test
    void shouldReleaseLockAfterError() {

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.error(new RuntimeException("Connection failed")),
                        Flux.just(createResponse("Успешный ответ"))
                );

        // Первый запрос завершается ошибкой
        assertThrows(
                ChatModelException.class,
                () -> chatService
                        .stream("conversation-1", "Первый вопрос")
                        .blockLast(Duration.ofSeconds(5))
        );

        // Второй запрос должен успешно выполниться
        String result = chatService
                .stream("conversation-1", "Второй вопрос")
                .blockFirst(Duration.ofSeconds(5));

        assertEquals("Успешный ответ", result);
    }

    @Test
    void shouldTrimHistoryWhenLimitExceeded() {

        when(props.getMaxHistoryTurns()).thenReturn(2);

        List<Prompt> prompts = new ArrayList<>();

        when(chatModel.stream(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    prompts.add(invocation.getArgument(0));
                    return Flux.just(createResponse("Ответ"));
                });

        chatService.stream("conversation-1", "Вопрос 1")
                .blockLast(Duration.ofSeconds(5));

        chatService.stream("conversation-1", "Вопрос 2")
                .blockLast(Duration.ofSeconds(5));

        chatService.stream("conversation-1", "Вопрос 3")
                .blockLast(Duration.ofSeconds(5));

        chatService.stream("conversation-1", "Вопрос 4")
                .blockLast(Duration.ofSeconds(5));

        List<Message> messages = prompts.get(3).getInstructions();

        assertEquals(7, messages.size());

        assertEquals("Вопрос 2", messages.get(1).getText());
        assertEquals("Ответ", messages.get(2).getText());

        assertEquals("Вопрос 3", messages.get(3).getText());
        assertEquals("Ответ", messages.get(4).getText());

        assertEquals("Вопрос 4", messages.get(6).getText());
    }

    @Test
    void shouldKeepConversationsIsolated() {

        List<Prompt> prompts = new ArrayList<>();

        when(chatModel.stream(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    prompts.add(invocation.getArgument(0));
                    return Flux.just(createResponse("Ответ"));
                });

        chatService.stream("conversation-1", "Вопрос A")
                .blockLast(Duration.ofSeconds(5));

        chatService.stream("conversation-2", "Вопрос B")
                .blockLast(Duration.ofSeconds(5));

        chatService.stream("conversation-1", "Второй вопрос A")
                .blockLast(Duration.ofSeconds(5));

        List<Message> messagesA = prompts.get(2).getInstructions();
        List<Message> messagesB = prompts.get(1).getInstructions();

        assertEquals(5, messagesA.size());
        assertEquals(3, messagesB.size());

        assertEquals("Вопрос A", messagesA.get(1).getText());
        assertEquals("Ответ", messagesA.get(2).getText());

        assertEquals("Вопрос B", messagesB.get(2).getText());
    }

    @Test
    void shouldUseConfiguredModelOptions() {

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(createResponse("Ответ")));

        chatService.stream("conversation-1", "Привет")
                .blockLast(Duration.ofSeconds(5));

        ArgumentCaptor<Prompt> captor =
                ArgumentCaptor.forClass(Prompt.class);

        verify(chatModel).stream(captor.capture());

        Prompt prompt = captor.getValue();

        OllamaChatOptions options =
                (OllamaChatOptions) prompt.getOptions();

        assertEquals("qwen3.5:4b", options.getModel());
        assertEquals(512, options.getNumPredict());
        assertEquals(0.2, options.getTemperature());
    }

    @Test
    void shouldReleaseLockWhenStreamIsCancelled() throws Exception {

        CountDownLatch subscribed = new CountDownLatch(1);

        when(chatModel.stream(any(Prompt.class)))
                .thenAnswer(invocation ->
                        Flux.<ChatResponse>never()
                                .doOnSubscribe(subscription ->
                                        subscribed.countDown()
                                )
                );

        var disposable = chatService
                .stream("conversation-1", "Первый вопрос")
                .subscribe();

        assertTrue(subscribed.await(5, TimeUnit.SECONDS));

        disposable.dispose();

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(createResponse("Успешный ответ")));

        String result = chatService
                .stream("conversation-1", "Второй вопрос")
                .blockFirst(Duration.ofSeconds(5));

        assertEquals("Успешный ответ", result);
    }

    @Test
    void shouldLimitConversationHistory() {

        when(props.getMaxHistoryTurns()).thenReturn(2);

        List<Prompt> prompts = new ArrayList<>();

        when(chatModel.stream(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    prompts.add(invocation.getArgument(0));
                    return Flux.just(createResponse("Ответ"));
                });

        for (int i = 1; i <= 5; i++) {
            chatService.stream(
                    "conversation-1",
                    "Вопрос " + i
            ).blockLast(Duration.ofSeconds(5));
        }

        List<Message> messages = prompts.get(4).getInstructions();
        assertAll(() -> {
                    assertEquals(7, messages.size());
                    assertEquals("Вопрос 3", messages.get(1).getText());
                    assertEquals("Ответ", messages.get(2).getText());
                    assertEquals("Вопрос 4", messages.get(3).getText());
                    assertEquals("Ответ", messages.get(4).getText());
                    assertTrue(messages.get(5).getText().startsWith("Current date"));
                }
        );
    }
    private ChatResponse createResponse(String text) {

        AssistantMessage output = new AssistantMessage(text);

        Generation generation = new Generation(output);

        return new ChatResponse(List.of(generation));
    }
}

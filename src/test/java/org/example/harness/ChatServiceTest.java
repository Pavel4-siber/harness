package org.example.harness;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final String CONVERSATION_ID = "conversation-1";

    private ChatService createService(
            OllamaChatModel chatModel,
            int maxHistoryTurns) {

        return new ChatService(
                chatModel,
                maxHistoryTurns,
                100,
                Duration.ofMinutes(60),
                "qwen3.5:4b",
                512,
                0.2,
                "  Be brief  ",
                "Europe/Moscow",
                Ticker.systemTicker()
        );
    }

    private ChatResponse response(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text)))
        );
    }

    private List<String> texts(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .toList();
    }

    private List<Message> systemMessages(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(message ->
                        message.getMessageType() == MessageType.SYSTEM)
                .toList();
    }

    @Test
    void addsSystemPromptAndTimeContextToEveryRequestWithoutStoringThemInHistory() {

        OllamaChatModel chatModel = mock(OllamaChatModel.class);

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(response("A1")))
                .thenReturn(Flux.just(response("A2")));

        ChatService chatService = createService(chatModel, 20);

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q1"))
                .expectNext("A1")
                .verifyComplete();

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q2"))
                .expectNext("A2")
                .verifyComplete();

        var promptCaptor =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);

        verify(chatModel, times(2)).stream(promptCaptor.capture());

        Prompt firstPrompt = promptCaptor.getAllValues().get(0);
        Prompt secondPrompt = promptCaptor.getAllValues().get(1);

        assertThat(texts(firstPrompt.getInstructions()))
                .containsExactly(
                        "Be brief",
                        firstPrompt.getInstructions().get(1).getText(),
                        "Q1"
                );

        assertThat(texts(secondPrompt.getInstructions()))
                .containsExactly(
                        "Be brief",
                        "Q1",
                        "A1",
                        secondPrompt.getInstructions().get(3).getText(),
                        "Q2"
                );

        List<Message> firstSystem = systemMessages(firstPrompt);

        assertThat(firstSystem).hasSize(2);
        assertThat(firstSystem.get(0).getText())
                .isEqualTo("Be brief");

        assertThat(firstSystem.get(1).getText())
                .contains("Current date and time:")
                .contains("Europe/Moscow");

        List<Message> secondSystem = systemMessages(secondPrompt);

        assertThat(secondSystem).hasSize(2);

        assertThat(secondSystem.get(0).getText())
                .isEqualTo("Be brief");

        assertThat(secondSystem.get(1).getText())
                .contains("Current date and time:")
                .contains("Europe/Moscow");

        assertThat(secondPrompt.getInstructions())
                .filteredOn(message ->
                        message.getMessageType() == MessageType.SYSTEM)
                .hasSize(2);
    }

    @Test
    void preservesConversationHistoryBetweenRequests() {

        OllamaChatModel chatModel = mock(OllamaChatModel.class);

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(response("A1")))
                .thenReturn(Flux.just(response("A2")));

        ChatService chatService = createService(chatModel, 20);

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q1"))
                .expectNext("A1")
                .verifyComplete();

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q2"))
                .expectNext("A2")
                .verifyComplete();

        var promptCaptor =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);

        verify(chatModel, times(2)).stream(promptCaptor.capture());

        Prompt secondPrompt = promptCaptor.getAllValues().get(1);

        assertThat(secondPrompt.getInstructions())
                .filteredOn(message ->
                        message.getMessageType() == MessageType.USER
                                || message.getMessageType() == MessageType.ASSISTANT)
                .extracting(Message::getText)
                .containsExactly("Q1", "A1", "Q2");
    }

    @Test
    void trimsHistoryToConfiguredNumberOfTurns() {

        OllamaChatModel chatModel = mock(OllamaChatModel.class);

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(response("A1")))
                .thenReturn(Flux.just(response("A2")))
                .thenReturn(Flux.just(response("A3")));

        ChatService chatService = createService(chatModel, 1);

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q1"))
                .expectNext("A1")
                .verifyComplete();

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q2"))
                .expectNext("A2")
                .verifyComplete();

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q3"))
                .expectNext("A3")
                .verifyComplete();

        var promptCaptor =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);

        verify(chatModel, times(3)).stream(promptCaptor.capture());

        Prompt thirdPrompt = promptCaptor.getAllValues().get(2);

        assertThat(thirdPrompt.getInstructions())
                .filteredOn(message ->
                        message.getMessageType() == MessageType.USER
                                || message.getMessageType() == MessageType.ASSISTANT)
                .extracting(Message::getText)
                .containsExactly("Q2", "A2", "Q3");
    }

    @Test
    void convertsModelErrorToChatModelException() {

        OllamaChatModel chatModel = mock(OllamaChatModel.class);

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(
                        new RuntimeException("Ollama unavailable")
                ));

        ChatService chatService = createService(chatModel, 20);

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q1"))
                .expectError(ChatModelException.class)
                .verify();
    }

    @Test
    void doesNotStoreEmptyModelResponse() {

        OllamaChatModel chatModel = mock(OllamaChatModel.class);

        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(response("   ")));

        ChatService chatService = createService(chatModel, 20);

        StepVerifier.create(chatService.stream(CONVERSATION_ID, "Q1"))
                .expectError(ChatModelException.class)
                .verify();
    }
}

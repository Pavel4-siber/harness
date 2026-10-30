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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class ChatServiceTests {

    @Test
    void sendsEarlierTurnsWithNextUserMessage() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("First answer"))
                .thenReturn(response("Second answer"));
        ChatService chatService = newService(chatModel, 20);

        assertThat(chatService.reply("conversation-1", "First question")).isEqualTo("First answer");
        assertThat(chatService.reply("conversation-1", "Second question")).isEqualTo("Second answer");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getAllValues().get(0).getInstructions()))
                .containsExactly("First question");
        assertThat(texts(promptCaptor.getAllValues().get(1).getInstructions()))
                .containsExactly("First question", "First answer", "Second question");
    }

    @Test
    void limitsHistoryToConfiguredNumberOfTurns() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("Answer 1"))
                .thenReturn(response("Answer 2"))
                .thenReturn(response("Answer 3"));
        ChatService chatService = newService(chatModel, 1);

        chatService.reply("conversation-1", "Question 1");
        chatService.reply("conversation-1", "Question 2");
        chatService.reply("conversation-1", "Question 3");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(3)).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getAllValues().get(2).getInstructions()))
                .containsExactly("Question 2", "Answer 2", "Question 3");
    }

    @Test
    void doesNotStoreTurnWhenModelCallFails() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("model failed"))
                .thenReturn(response("Recovered"));
        ChatService chatService = newService(chatModel, 20);

        assertThatThrownBy(() -> chatService.reply("conversation-1", "Failed question"))
                .isInstanceOf(ChatModelException.class);
        chatService.reply("conversation-1", "Retry question");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getAllValues().get(1).getInstructions()))
                .containsExactly("Retry question");
    }

    @Test
    void passesConfiguredModelSettingsToTheRequest() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("Answer"));
        ChatService chatService = new ChatService(chatModel, 20, 100, 60,
                "test-model", 42, 0.5, "", "Europe/Moscow");

        chatService.reply("conversation-1", "Question");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("test-model");
        assertThat(promptCaptor.getValue().getOptions().getMaxTokens()).isEqualTo(42);
        assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.5);
    }

    @Test
    void addsSystemPromptToEveryRequestWithoutStoringItInHistory() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("A1"))
                .thenReturn(response("A2"));
        ChatService chatService = new ChatService(chatModel, 20, 100, 60,
                "m", 42, 0.2, "  Be brief  ", "Europe/Moscow");

        chatService.reply("conversation-1", "Q1");
        chatService.reply("conversation-1", "Q2");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptCaptor.capture());
        List<Message> second = promptCaptor.getAllValues().get(1).getInstructions();
        assertThat(texts(promptCaptor.getAllValues().get(0).getInstructions()))
                .containsExactly("Q1");
        assertThat(texts(second)).containsExactly("Q1", "A1", "Q2");
        assertThat(second.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM).count())
                .isEqualTo(1);
        assertThat(second.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(second.get(0).getText())
                .startsWith("Be brief")
                .contains("Current date and time:")
                .contains("Europe/Moscow");
    }

    @Test
    void streamsChunksAndStoresFullAnswerInHistory() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("Hel"), response("lo")));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("Next answer"));
        ChatService chatService = newService(chatModel, 20);

        List<String> chunks = chatService.stream("conversation-1", "Question").collectList().block();
        chatService.reply("conversation-1", "Follow-up");

        assertThat(chunks).containsExactly("Hel", "lo");
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getValue().getInstructions()))
                .containsExactly("Question", "Hello", "Follow-up");
    }

    @Test
    void doesNotStoreTurnAndReleasesConversationWhenStreamFails() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("model failed")));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("Recovered"));
        ChatService chatService = newService(chatModel, 20);

        assertThatThrownBy(() -> chatService.stream("conversation-1", "Failed question")
                .collectList().block())
                .isInstanceOf(ChatModelException.class);

        chatService.reply("conversation-1", "Retry question");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getValue().getInstructions()))
                .containsExactly("Retry question");
    }

    @Test
    void doesNotStoreTurnWhenStreamCompletesWithoutText() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("Recovered"));
        ChatService chatService = newService(chatModel, 20);

        assertThatThrownBy(() -> chatService.stream("conversation-1", "Empty reply")
                .collectList().block())
                .isInstanceOf(ChatModelException.class);

        chatService.reply("conversation-1", "Retry question");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getValue().getInstructions()))
                .containsExactly("Retry question");
    }

    @Test
    void releasesConversationAndDoesNotStoreTurnWhenStreamIsCancelled() throws Exception {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.create(sink -> {
            sink.next(response("Partial"));
            firstChunkSent.countDown();
        }));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("Recovered"));
        ChatService chatService = newService(chatModel, 20);

        var subscription = chatService.stream("conversation-1", "Cancelled question").subscribe();
        assertThat(firstChunkSent.await(2, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();

        assertThat(CompletableFuture.supplyAsync(() -> chatService.reply("conversation-1", "Retry question"))
                .get(2, TimeUnit.SECONDS)).isEqualTo("Recovered");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getValue().getInstructions()))
                .containsExactly("Retry question");
    }

    @Test
    void serializesStreamingAndBlockingRequestsForTheSameConversation() throws Exception {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        CountDownLatch streamStarted = new CountDownLatch(1);
        CountDownLatch releaseStream = new CountDownLatch(1);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.create(sink -> {
            streamStarted.countDown();
            try {
                releaseStream.await();
                sink.next(response("Stream answer"));
                sink.complete();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                sink.error(exception);
            }
        }));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("Blocking answer"));
        ChatService chatService = newService(chatModel, 20);

        CompletableFuture<List<String>> streaming = CompletableFuture.supplyAsync(() ->
                chatService.stream("conversation-1", "Streaming question").collectList().block());
        assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<String> blocking = CompletableFuture.supplyAsync(() ->
                chatService.reply("conversation-1", "Blocking question"));
        verify(chatModel, after(200).never()).call(any(Prompt.class));

        releaseStream.countDown();
        assertThat(streaming.get(2, TimeUnit.SECONDS)).containsExactly("Stream answer");
        assertThat(blocking.get(2, TimeUnit.SECONDS)).isEqualTo("Blocking answer");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getValue().getInstructions()))
                .containsExactly("Streaming question", "Stream answer", "Blocking question");
    }

    @Test
    void startsNewConversationAfterTtlExpires() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("First answer"))
                .thenReturn(response("Second answer"));
        AtomicLong now = new AtomicLong();
        Ticker ticker = now::get;
        ChatService chatService = new ChatService(chatModel, 20, 1000, Duration.ofMinutes(1),
                "qwen3.5:4b", 512, 0.2, "", "Europe/Moscow", ticker);

        chatService.reply("conversation-1", "First question");
        now.addAndGet(Duration.ofMinutes(2).toNanos());
        chatService.reply("conversation-1", "Second question");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getAllValues().get(1).getInstructions()))
                .containsExactly("Second question");
    }

    private static ChatService newService(OllamaChatModel chatModel, int maxHistoryTurns) {
        return new ChatService(chatModel, maxHistoryTurns, 1000, 60, "qwen3.5:4b",
                512, 0.2, "", "Europe/Moscow");
    }

    private static ChatResponse response(String answer) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream()
                .filter(message -> message.getMessageType() != MessageType.SYSTEM)
                .map(Message::getText)
                .toList();
    }
}

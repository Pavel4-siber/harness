package org.example.harness;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ChatServiceTests {

    @Test
    void sendsEarlierTurnsWithNextUserMessage() {
        OllamaChatModel chatModel = mock(OllamaChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("First answer"))
                .thenReturn(response("Second answer"));
        ChatService chatService = new ChatService(chatModel, 20);

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
        ChatService chatService = new ChatService(chatModel, 1);

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
        ChatService chatService = new ChatService(chatModel, 20);

        assertThatThrownBy(() -> chatService.reply("conversation-1", "Failed question"))
                .isInstanceOf(ChatModelException.class);
        chatService.reply("conversation-1", "Retry question");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptCaptor.capture());
        assertThat(texts(promptCaptor.getAllValues().get(1).getInstructions()))
                .containsExactly("Retry question");
    }

    private static ChatResponse response(String answer) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }
}

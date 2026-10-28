package org.example.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTests {

    private final ChatService chatService = mock(ChatService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService))
                .setControllerAdvice(new ChatExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void createsConversationIdWhenNoneWasProvided() throws Exception {
        when(chatService.reply(anyString(), eq("Hi"))).thenReturn("Hello");

        String body = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"Hi\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("conversationId").asText()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(response.get("message").asText()).isEqualTo("Hello");
        verify(chatService).reply(response.get("conversationId").asText(), "Hi");
    }

    @Test
    void continuesConversationWithProvidedId() throws Exception {
        when(chatService.reply("conversation-123", "Next question")).thenReturn("Next answer");

        String body = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"conversationId\":\"conversation-123\",\"message\":\"Next question\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("conversationId").asText()).isEqualTo("conversation-123");
        assertThat(response.get("message").asText()).isEqualTo("Next answer");
    }

    @Test
    void rejectsBlankMessageWithValidationError() throws Exception {
        String body = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.get("details").toString()).contains("message");
    }

    @Test
    void returnsServiceUnavailableWhenModelCannotBeReached() throws Exception {
        when(chatService.reply("conversation-123", "Hi"))
                .thenThrow(new ChatModelException(new ResourceAccessException("connection refused")));

        String body = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"conversationId\":\"conversation-123\",\"message\":\"Hi\"}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("code").asText()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(response.get("message").asText()).doesNotContain("connection refused");
    }
}

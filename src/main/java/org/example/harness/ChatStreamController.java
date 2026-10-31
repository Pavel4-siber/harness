package org.example.harness;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

@RestController
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);
    private final ChatService chatService;

    public ChatStreamController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> stream(@Valid @RequestBody ChatRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();

        Flux<ServerSentEvent<Map<String, String>>> token = chatService.stream(conversationId, request.message())
                .map(text -> event("token", Map.of("text", text)));

        return Flux.just(event("meta", Map.of("conversationId", conversationId)))
                .concatWith(token)
                .concatWith(Flux.just(event("done", Map.of())))
                .onErrorResume(error -> {
        log.error("Streaming chat request failed", error);
        return Flux.just(event("error", Map.of(
                "code", "MODEL_ERROR",
                "message", "The model failed to produce a response")));
        });
    }

    private static ServerSentEvent<Map<String, String>> event(String name, Map<String, String> data) {
        return ServerSentEvent.<Map<String, String>>builder().event(name).data(data).build();
    }
}

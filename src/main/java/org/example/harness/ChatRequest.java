package org.example.harness;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(String conversationId,
                          @NotBlank(message = "message must not be blank") String message) {
}

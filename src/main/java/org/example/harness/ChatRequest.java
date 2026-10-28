package org.example.harness;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @Size(max = 64, message = "conversationId must be at most 64 characters") String conversationId,
        @NotBlank(message = "message must not be blank")
        @Size(max = 8000, message = "message must be at most 8000 characters") String message) {
}

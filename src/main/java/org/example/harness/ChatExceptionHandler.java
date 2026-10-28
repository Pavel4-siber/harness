package org.example.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

@RestControllerAdvice
public class ChatExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is missing or invalid", List.of());
    }

    @ExceptionHandler(ChatModelException.class)
    public ResponseEntity<ApiErrorResponse> handleChatModel(ChatModelException exception) {
        if (hasCause(exception, ResourceAccessException.class)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE",
                    "The model service is currently unavailable", List.of());
        }
        log.warn("Chat model request failed", exception);
        return error(HttpStatus.BAD_GATEWAY, "MODEL_ERROR",
                "The model service could not complete the request", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            String message = status.is4xxClientError()
                    ? "Request could not be processed"
                    : "An unexpected error occurred";
            return error(status, "HTTP_ERROR", message, List.of());
        }
        log.error("Unexpected error while handling chat request", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of());
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
        Throwable current = exception;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatusCode status, String code, String message, List<String> details) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(status.value(), code, message, details));
    }
}

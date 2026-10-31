package org.example.harness;

public class ChatModelException extends RuntimeException {

    public ChatModelException(Throwable cause) {
        super(cause);
    }

    public ChatModelException(String message, Throwable cause) {
        super("Chat model request failed", cause);
    }
}

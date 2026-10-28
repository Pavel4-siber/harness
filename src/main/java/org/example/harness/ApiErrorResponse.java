package org.example.harness;

import java.util.List;

public record ApiErrorResponse(int status, String code, String message, List<String> details) {
}

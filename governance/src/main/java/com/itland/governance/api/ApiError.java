package com.itland.governance.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Structured error body for every non-2xx response. */
public record ApiError(String code, String message, Instant timestamp, List<String> errors, Map<String, Object> details) {
  public static ApiError of(String code, String message) {
    return new ApiError(code, message, Instant.now(), List.of(), Map.of());
  }
}

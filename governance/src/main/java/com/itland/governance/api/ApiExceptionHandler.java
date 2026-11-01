package com.itland.governance.api;

import com.itland.governance.application.approvals.ApprovalService;
import com.itland.governance.application.policy.NotFoundException;
import com.itland.governance.application.submission.SubmissionExceptions;
import com.itland.governance.infrastructure.opa.OpaException;
import com.itland.governance.infrastructure.security.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(SubmissionExceptions.ManifestValidationException.class)
  public ResponseEntity<ApiError> validation(SubmissionExceptions.ManifestValidationException e) {
    return ResponseEntity.badRequest().body(new ApiError("MANIFEST_INVALID", "Manifest failed structural validation", Instant.now(), e.errors(), Map.of()));
  }

  @ExceptionHandler({WebExchangeBindException.class, ServerWebInputException.class, IllegalArgumentException.class})
  public ResponseEntity<ApiError> badRequest(Exception e) {
    List<String> errors = e instanceof WebExchangeBindException b
        ? b.getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).toList()
        : List.of(e.getMessage() == null ? "invalid request" : e.getMessage());
    return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Invalid request", Instant.now(), errors, Map.of()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> forbidden(AccessDeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", e.getMessage()));
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  public ResponseEntity<ApiError> springForbidden(org.springframework.security.access.AccessDeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", "Insufficient role"));
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ApiError> notFound(NotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", e.getMessage()));
  }

  @ExceptionHandler(SubmissionExceptions.IdempotencyConflictException.class)
  public ResponseEntity<ApiError> idempotency(SubmissionExceptions.IdempotencyConflictException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("IDEMPOTENCY_CONFLICT", e.getMessage(), Instant.now(), List.of(), Map.of("requestId", e.requestId())));
  }

  @ExceptionHandler(ApprovalService.ApprovalRuleException.class)
  public ResponseEntity<ApiError> approvalRule(ApprovalService.ApprovalRuleException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("APPROVAL_RULE", e.getMessage()));
  }

  @ExceptionHandler(SubmissionExceptions.EvaluationUnavailableException.class)
  public ResponseEntity<ApiError> evaluationUnavailable(SubmissionExceptions.EvaluationUnavailableException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "10")
        .body(new ApiError("POLICY_EVALUATION_UNAVAILABLE", "The policy engine could not evaluate the request; it was recorded and can be retried with the same Idempotency-Key",
            Instant.now(), List.of(), Map.of("requestId", e.decision().requestId(), "decisionId", e.decision().id())));
  }

  @ExceptionHandler(OpaException.class)
  public ResponseEntity<ApiError> policyEngine(OpaException e) {
    log.warn("Policy engine unavailable: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "10")
        .body(ApiError.of("POLICY_ENGINE_UNAVAILABLE", "The policy engine could not answer: " + e.getMessage()));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> conflict(IllegalStateException e) {
    log.warn("State conflict: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("STATE_CONFLICT", e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception e) {
    log.error("Unhandled API error", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("INTERNAL_ERROR", "Unexpected error"));
  }
}

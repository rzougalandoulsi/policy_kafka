package com.itland.governance.application.submission;

import com.itland.governance.domain.decision.GovernanceDecision;

import java.util.List;

public final class SubmissionExceptions {
  private SubmissionExceptions() {}

  public static class ManifestValidationException extends RuntimeException {
    private final List<String> errors;
    public ManifestValidationException(List<String> errors) {
      super("Manifest validation failed: " + errors);
      this.errors = errors;
    }
    public List<String> errors() { return errors; }
  }

  public static class IdempotencyConflictException extends RuntimeException {
    private final String requestId;
    public IdempotencyConflictException(String requestId) {
      super("Idempotency-Key was already used with a different manifest (request " + requestId + ")");
      this.requestId = requestId;
    }
    public String requestId() { return requestId; }
  }

  /** The request was persisted but the policy engine could not produce a trustworthy decision. */
  public static class EvaluationUnavailableException extends RuntimeException {
    private final GovernanceDecision decision;
    public EvaluationUnavailableException(GovernanceDecision decision) {
      super("Policy evaluation unavailable: " + decision.errorMessage());
      this.decision = decision;
    }
    public GovernanceDecision decision() { return decision; }
  }
}

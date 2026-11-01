package com.itland.governance.domain.decision;

/**
 * One policy violation as reported by OPA. severity: HARD_DENY (never exemptable, never approvable) or DENY.
 * exempted=true means a scoped exception matched a bypassable policy, so the violation does not block.
 */
public record PolicyViolation(
    String policyId,
    String severity,
    String message,
    String resourceType,
    String resourceName,
    boolean bypassable,
    boolean exempted,
    String exceptionId) {

  public static final String HARD_DENY = "HARD_DENY";
  public static final String DENY = "DENY";

  public boolean blocking() {
    return !exempted;
  }

  public boolean hardDeny() {
    return HARD_DENY.equals(severity);
  }
}

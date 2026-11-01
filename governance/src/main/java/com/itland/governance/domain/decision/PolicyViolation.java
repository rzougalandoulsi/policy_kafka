package com.itland.governance.domain.decision;

/**
 * One policy violation as reported by OPA. severity: HARD_DENY (never exemptable, never approvable) or DENY.
 * exempted=true means a scoped exception matched a bypassable policy, so the violation does not block.
 * enforcement: "enforce" (default) or "shadow"; a shadow violation is recorded as a finding and never blocks.
 */
public record PolicyViolation(
    String policyId,
    String severity,
    String message,
    String resourceType,
    String resourceName,
    boolean bypassable,
    boolean exempted,
    String exceptionId,
    String enforcement) {

  public static final String HARD_DENY = "HARD_DENY";
  public static final String DENY = "DENY";
  public static final String ENFORCE = "enforce";
  public static final String SHADOW = "shadow";

  public PolicyViolation {
    // Rows written before contract 2.0 have no enforcement field: they were enforced.
    enforcement = SHADOW.equals(enforcement) ? SHADOW : ENFORCE;
  }

  public boolean blocking() {
    return !exempted && !shadow();
  }

  public boolean shadow() {
    return SHADOW.equals(enforcement);
  }

  public boolean hardDeny() {
    return HARD_DENY.equals(severity);
  }
}

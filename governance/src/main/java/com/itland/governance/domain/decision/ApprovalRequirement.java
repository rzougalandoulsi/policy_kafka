package com.itland.governance.domain.decision;

/** An approval requirement raised by OPA. A requirement in shadow mode is recorded but never opens an approval. */
public record ApprovalRequirement(String policyId, String message, String resourceType, String resourceName, String enforcement) {

  public ApprovalRequirement {
    enforcement = PolicyViolation.SHADOW.equals(enforcement) ? PolicyViolation.SHADOW : PolicyViolation.ENFORCE;
  }

  public boolean shadow() {
    return PolicyViolation.SHADOW.equals(enforcement);
  }
}

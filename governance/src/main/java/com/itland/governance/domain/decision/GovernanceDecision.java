package com.itland.governance.domain.decision;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GovernanceDecision(
    String id,
    String requestId,
    String planId,
    GovernanceStage stage,
    DecisionType type,
    /** Policy release id (the tested set of bundle revisions), or "" when none was active. */
    String policyRevision,
    /** Revision per bundle name that OPA reported for this decision. */
    Map<String, String> policyRevisions,
    long configurationVersion,
    String digest,
    List<String> matchedPolicies,
    List<PolicyViolation> violations,
    List<ApprovalRequirement> approvalRequirements,
    List<String> appliedExceptions,
    String errorMessage,
    Long opaLatencyMs,
    Instant evaluatedAt) {

  public List<PolicyViolation> blockingViolations() {
    return violations.stream().filter(PolicyViolation::blocking).toList();
  }
}

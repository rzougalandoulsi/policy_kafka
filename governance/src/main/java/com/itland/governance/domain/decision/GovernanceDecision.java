package com.itland.governance.domain.decision;

import java.time.Instant;
import java.util.List;

public record GovernanceDecision(
    String id,
    String requestId,
    String planId,
    GovernanceStage stage,
    DecisionType type,
    String policyRevision,
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

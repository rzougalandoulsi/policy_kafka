package com.itland.governance.application.evaluation;

import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.PolicyViolation;
import com.itland.governance.infrastructure.opa.OpaEvaluation;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Deterministic aggregation of OPA output. Precedence: hard deny > deny > approval required > allow.
 * A hard-deny violation can never be exempted or approved around; only bypassable policies honour
 * exceptions. The Java aggregate is compared with OPA's own "decision" field as a consistency check.
 */
public final class DecisionAggregator {

  public record Aggregate(DecisionType type, List<PolicyViolation> violations, List<ApprovalRequirement> approvals,
                          List<String> matchedPolicies, List<String> appliedExceptions, boolean consistentWithOpa) {}

  private DecisionAggregator() {}

  public static Aggregate aggregate(OpaEvaluation.Result result, Set<String> knownExceptionIds) {
    List<PolicyViolation> violations = result.violations().stream()
        .map(v -> normalize(v, knownExceptionIds))
        .sorted(Comparator.comparing(PolicyViolation::policyId).thenComparing(PolicyViolation::resourceName, Comparator.nullsFirst(String::compareTo)))
        .toList();
    List<ApprovalRequirement> approvals = result.approvals().stream()
        .map(a -> new ApprovalRequirement(a.policyId(), a.message(), a.resourceType(), a.resourceName()))
        .sorted(Comparator.comparing(ApprovalRequirement::policyId).thenComparing(ApprovalRequirement::resourceName, Comparator.nullsFirst(String::compareTo)))
        .toList();

    DecisionType type;
    if (violations.stream().anyMatch(PolicyViolation::blocking)) {
      type = DecisionType.DENY;
    } else if (!approvals.isEmpty()) {
      type = DecisionType.REQUIRE_APPROVAL;
    } else {
      type = DecisionType.ALLOW;
    }
    List<String> applied = violations.stream().filter(PolicyViolation::exempted).map(PolicyViolation::exceptionId).distinct().sorted().toList();
    boolean consistent = type.name().equals(result.decision());
    return new Aggregate(type, violations, approvals, result.matchedPolicies().stream().sorted().toList(), applied, consistent);
  }

  /** Exemptions are only honoured for bypassable, non-hard-deny policies and only for exceptions we resolved ourselves. */
  private static PolicyViolation normalize(OpaEvaluation.Violation v, Set<String> knownExceptionIds) {
    boolean hard = PolicyViolation.HARD_DENY.equals(v.severity());
    boolean bypassable = !hard && Boolean.TRUE.equals(v.bypassable());
    boolean exempted = bypassable && Boolean.TRUE.equals(v.exempted()) && v.exceptionId() != null && knownExceptionIds.contains(v.exceptionId());
    return new PolicyViolation(v.policyId(), hard ? PolicyViolation.HARD_DENY : PolicyViolation.DENY, v.message(),
        v.resourceType() == null ? "" : v.resourceType(), v.resourceName() == null ? "" : v.resourceName(),
        bypassable, exempted, exempted ? v.exceptionId() : null);
  }
}

package com.itland.governance.application.evaluation;

import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.infrastructure.opa.OpaEvaluation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionAggregatorTest {

  private static OpaEvaluation.Violation v(String id, String severity, boolean bypassable, boolean exempted, String exc) {
    return new OpaEvaluation.Violation(id, severity, "m", "TOPIC", "t", bypassable, exempted, exc);
  }

  private static OpaEvaluation.Result result(String decision, List<OpaEvaluation.Violation> violations, List<OpaEvaluation.Approval> approvals) {
    return new OpaEvaluation.Result(decision, violations, approvals, List.of(), List.of(), true, java.util.Map.of(), java.util.Map.of("core", "core-r1"));
  }

  @Test
  void allowWhenNothingMatches() {
    var agg = DecisionAggregator.aggregate(result("ALLOW", List.of(), List.of()), Set.of());
    assertThat(agg.type()).isEqualTo(DecisionType.ALLOW);
    assertThat(agg.consistentWithOpa()).isTrue();
  }

  @Test
  void blockingViolationDenies() {
    var agg = DecisionAggregator.aggregate(result("DENY", List.of(v("P", "DENY", true, false, null)), List.of()), Set.of());
    assertThat(agg.type()).isEqualTo(DecisionType.DENY);
  }

  @Test
  void exemptedBypassableViolationDoesNotBlock() {
    var agg = DecisionAggregator.aggregate(result("ALLOW", List.of(v("P", "DENY", true, true, "exc-1")), List.of()), Set.of("exc-1"));
    assertThat(agg.type()).isEqualTo(DecisionType.ALLOW);
    assertThat(agg.appliedExceptions()).containsExactly("exc-1");
  }

  @Test
  void exemptionIsIgnoredWhenExceptionWasNotResolvedByGovernance() {
    var agg = DecisionAggregator.aggregate(result("ALLOW", List.of(v("P", "DENY", true, true, "exc-forged")), List.of()), Set.of("exc-1"));
    assertThat(agg.type()).isEqualTo(DecisionType.DENY);
    assertThat(agg.consistentWithOpa()).isFalse();
  }

  @Test
  void hardDenyCanNeverBeExempted() {
    var agg = DecisionAggregator.aggregate(result("DENY", List.of(v("ADMIN", "HARD_DENY", true, true, "exc-1")), List.of()), Set.of("exc-1"));
    assertThat(agg.type()).isEqualTo(DecisionType.DENY);
    assertThat(agg.violations().get(0).exempted()).isFalse();
    assertThat(agg.violations().get(0).bypassable()).isFalse();
  }

  @Test
  void approvalRequiredOnlyWithoutBlockingViolations() {
    var approval = new OpaEvaluation.Approval("A", "m", "TOPIC", "t");
    assertThat(DecisionAggregator.aggregate(result("REQUIRE_APPROVAL", List.of(), List.of(approval)), Set.of()).type()).isEqualTo(DecisionType.REQUIRE_APPROVAL);
    assertThat(DecisionAggregator.aggregate(result("DENY", List.of(v("P", "DENY", true, false, null)), List.of(approval)), Set.of()).type()).isEqualTo(DecisionType.DENY);
  }

  @Test
  void violationsAreSortedDeterministically() {
    var agg = DecisionAggregator.aggregate(result("DENY", List.of(
        new OpaEvaluation.Violation("Z", "DENY", "m", "TOPIC", "b", true, false, null),
        new OpaEvaluation.Violation("A", "DENY", "m", "TOPIC", "z", true, false, null),
        new OpaEvaluation.Violation("A", "DENY", "m", "TOPIC", "a", true, false, null)), List.of()), Set.of());
    assertThat(agg.violations()).extracting(x -> x.policyId() + "/" + x.resourceName()).containsExactly("A/a", "A/z", "Z/b");
  }
}

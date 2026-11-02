package com.itland.governance.domain.approval;

import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.approval.ApprovalEngine;
import com.itland.governance.infrastructure.security.CallerContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRulesTest {

  private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

  private static ApprovalRequest approval(int required, boolean sod, Instant expires, ApprovalStatus status) {
    return new ApprovalRequest("apr-1", "req-1", GovernanceStage.ACTION_PLAN, "dec-1", "plan-1", "digest", "digest", status, "requester",
        required, List.of("governance-approver"), sod, List.of(), expires, 0, NOW.minusSeconds(60), null, null, ApprovalEngine.FLOWABLE, null);
  }

  private static CallerContext caller(String id, String... roles) {
    return new CallerContext(id, Set.of("team-a"), Set.of("development"), Set.of(roles));
  }

  private static ApprovalDecision decision(String by, ApprovalVerdict v) {
    return new ApprovalDecision("d-" + by, "apr-1", by, v, null, NOW);
  }

  @Test
  void approverWithRoleIsEligible() {
    assertThat(ApprovalRules.eligibility(approval(1, true, NOW.plusSeconds(60), ApprovalStatus.PENDING), caller("alice", "governance-approver"), List.of(), NOW)).isEmpty();
  }

  @Test
  void missingRoleIsRejected() {
    assertThat(ApprovalRules.eligibility(approval(1, true, NOW.plusSeconds(60), ApprovalStatus.PENDING), caller("alice", "developer"), List.of(), NOW)).isPresent();
  }

  @Test
  void separationOfDutiesBlocksRequester() {
    assertThat(ApprovalRules.eligibility(approval(1, true, NOW.plusSeconds(60), ApprovalStatus.PENDING), caller("requester", "governance-approver"), List.of(), NOW))
        .hasValueSatisfying(r -> assertThat(r).contains("Separation of duties"));
    assertThat(ApprovalRules.eligibility(approval(1, false, NOW.plusSeconds(60), ApprovalStatus.PENDING), caller("requester", "governance-approver"), List.of(), NOW)).isEmpty();
  }

  @Test
  void expiredOrResolvedApprovalsRejectDecisions() {
    assertThat(ApprovalRules.eligibility(approval(1, true, NOW.minusSeconds(1), ApprovalStatus.PENDING), caller("alice", "governance-approver"), List.of(), NOW)).isPresent();
    assertThat(ApprovalRules.eligibility(approval(1, true, NOW.plusSeconds(60), ApprovalStatus.APPROVED), caller("alice", "governance-approver"), List.of(), NOW)).isPresent();
  }

  @Test
  void quorumAndRejection() {
    ApprovalRequest two = approval(2, true, NOW.plusSeconds(60), ApprovalStatus.PENDING);
    assertThat(ApprovalRules.outcome(two, List.of(decision("a", ApprovalVerdict.APPROVE)))).isEqualTo(ApprovalStatus.PENDING);
    assertThat(ApprovalRules.outcome(two, List.of(decision("a", ApprovalVerdict.APPROVE), decision("b", ApprovalVerdict.APPROVE)))).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(ApprovalRules.outcome(two, List.of(decision("a", ApprovalVerdict.APPROVE), decision("b", ApprovalVerdict.REJECT)))).isEqualTo(ApprovalStatus.REJECTED);
  }
}

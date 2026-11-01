package com.itland.governance.domain.approval;

import com.itland.governance.infrastructure.security.CallerContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Pure approval rules: eligibility, separation of duties, quorum, expiry. */
public final class ApprovalRules {

  private ApprovalRules() {}

  /** Returns a rejection reason, or empty when the approver may decide on this approval. */
  public static Optional<String> eligibility(ApprovalRequest approval, CallerContext approver, List<ApprovalDecision> existing, Instant now) {
    if (approval.status() != ApprovalStatus.PENDING) {
      return Optional.of("Approval is " + approval.status());
    }
    if (!approval.expiresAt().isAfter(now)) {
      return Optional.of("Approval expired at " + approval.expiresAt());
    }
    if (approval.approverRoles().stream().noneMatch(approver::hasRole)) {
      return Optional.of("Approver lacks one of the required roles " + approval.approverRoles());
    }
    if (approval.separationOfDuties() && approver.principalId().equals(approval.requesterId())) {
      return Optional.of("Separation of duties: the requester cannot approve their own request");
    }
    return Optional.empty();
  }

  /** Outcome after adding a verdict: APPROVED when quorum reached, REJECTED on any reject, else still PENDING. */
  public static ApprovalStatus outcome(ApprovalRequest approval, List<ApprovalDecision> decisions) {
    if (decisions.stream().anyMatch(d -> d.verdict() == ApprovalVerdict.REJECT)) {
      return ApprovalStatus.REJECTED;
    }
    long approvals = decisions.stream().filter(d -> d.verdict() == ApprovalVerdict.APPROVE)
        .map(ApprovalDecision::approverId).distinct().count();
    return approvals >= approval.requiredApprovals() ? ApprovalStatus.APPROVED : ApprovalStatus.PENDING;
  }
}

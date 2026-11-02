package com.itland.governance.domain.approval;

import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.GovernanceStage;

import java.time.Instant;
import java.util.List;

public record ApprovalRequest(
    String id,
    String requestId,
    GovernanceStage stage,
    String decisionId,
    String planId,
    String planDigest,
    String digest,
    ApprovalStatus status,
    String requesterId,
    int requiredApprovals,
    List<String> approverRoles,
    boolean separationOfDuties,
    List<ApprovalRequirement> requirements,
    Instant expiresAt,
    long version,
    Instant createdAt,
    Instant resolvedAt,
    String resolutionReason,
    /** Implementation that resolves this approval (ADR 0011). */
    ApprovalEngine engine,
    /** Flowable process instance correlated with this approval (its business key is the approval id); null for pure legacy rows. */
    String processInstanceId) {}

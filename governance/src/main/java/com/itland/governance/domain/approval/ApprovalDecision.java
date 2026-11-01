package com.itland.governance.domain.approval;

import java.time.Instant;

public record ApprovalDecision(String id, String approvalId, String approverId, ApprovalVerdict verdict, String comment, Instant decidedAt) {}

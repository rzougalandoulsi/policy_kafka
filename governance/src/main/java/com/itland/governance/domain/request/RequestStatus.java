package com.itland.governance.domain.request;

import java.util.Set;

public enum RequestStatus {
  RECEIVED,
  STAGE1_DENIED,
  PENDING_APPROVAL_STAGE1,
  SUBMITTED,
  PLAN_PROPOSED,
  PENDING_APPROVAL_STAGE2,
  PLAN_AUTHORIZED,
  PLAN_REJECTED,
  EVALUATION_ERROR,
  APPROVAL_EXPIRED,
  STALE_PLAN,
  REJECTED_BY_ORCHESTRATOR,
  COMPLETED,
  FAILED;

  private static final Set<RequestStatus> TERMINAL = Set.of(
      STAGE1_DENIED, PLAN_REJECTED, APPROVAL_EXPIRED, STALE_PLAN, REJECTED_BY_ORCHESTRATOR, COMPLETED, FAILED);

  public boolean terminal() {
    return TERMINAL.contains(this);
  }
}

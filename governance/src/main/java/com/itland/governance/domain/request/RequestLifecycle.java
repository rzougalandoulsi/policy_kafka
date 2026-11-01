package com.itland.governance.domain.request;

import java.util.Map;
import java.util.Set;

import static com.itland.governance.domain.request.RequestStatus.*;

/** Legal state transitions. Anything not listed here is rejected and recorded as out-of-order. */
public final class RequestLifecycle {

  private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED = Map.ofEntries(
      Map.entry(RECEIVED, Set.of(STAGE1_DENIED, PENDING_APPROVAL_STAGE1, SUBMITTED, EVALUATION_ERROR)),
      Map.entry(EVALUATION_ERROR, Set.of(STAGE1_DENIED, PENDING_APPROVAL_STAGE1, SUBMITTED, PLAN_AUTHORIZED, PLAN_REJECTED, PENDING_APPROVAL_STAGE2, PLAN_PROPOSED)),
      Map.entry(PENDING_APPROVAL_STAGE1, Set.of(SUBMITTED, STAGE1_DENIED, APPROVAL_EXPIRED)),
      Map.entry(SUBMITTED, Set.of(PLAN_PROPOSED, REJECTED_BY_ORCHESTRATOR)),
      Map.entry(PLAN_PROPOSED, Set.of(PLAN_AUTHORIZED, PLAN_REJECTED, PENDING_APPROVAL_STAGE2, EVALUATION_ERROR, REJECTED_BY_ORCHESTRATOR)),
      Map.entry(PENDING_APPROVAL_STAGE2, Set.of(PLAN_AUTHORIZED, PLAN_REJECTED, APPROVAL_EXPIRED, STALE_PLAN, REJECTED_BY_ORCHESTRATOR)),
      Map.entry(PLAN_AUTHORIZED, Set.of(COMPLETED, FAILED, STALE_PLAN, REJECTED_BY_ORCHESTRATOR)),
      Map.entry(STAGE1_DENIED, Set.of()),
      Map.entry(PLAN_REJECTED, Set.of()),
      Map.entry(APPROVAL_EXPIRED, Set.of()),
      Map.entry(STALE_PLAN, Set.of()),
      Map.entry(REJECTED_BY_ORCHESTRATOR, Set.of()),
      Map.entry(COMPLETED, Set.of()),
      Map.entry(FAILED, Set.of()));

  private RequestLifecycle() {}

  public static boolean canTransition(RequestStatus from, RequestStatus to) {
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public static RequestStatus transition(RequestStatus from, RequestStatus to) {
    if (!canTransition(from, to)) {
      throw new IllegalTransitionException(from, to);
    }
    return to;
  }

  public static class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(RequestStatus from, RequestStatus to) {
      super("Illegal request transition " + from + " -> " + to);
    }
  }
}

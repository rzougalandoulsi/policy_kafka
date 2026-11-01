package com.itland.governance.domain.request;

import org.junit.jupiter.api.Test;

import static com.itland.governance.domain.request.RequestStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLifecycleTest {

  @Test
  void happyPath() {
    assertThat(RequestLifecycle.canTransition(RECEIVED, SUBMITTED)).isTrue();
    assertThat(RequestLifecycle.canTransition(SUBMITTED, PLAN_PROPOSED)).isTrue();
    assertThat(RequestLifecycle.canTransition(PLAN_PROPOSED, PLAN_AUTHORIZED)).isTrue();
    assertThat(RequestLifecycle.canTransition(PLAN_AUTHORIZED, COMPLETED)).isTrue();
  }

  @Test
  void terminalStatesAcceptNothing() {
    for (RequestStatus s : RequestStatus.values()) {
      if (s.terminal()) {
        for (RequestStatus t : RequestStatus.values()) {
          assertThat(RequestLifecycle.canTransition(s, t)).as(s + " -> " + t).isFalse();
        }
      }
    }
  }

  @Test
  void outOfOrderEventsAreRejected() {
    assertThat(RequestLifecycle.canTransition(SUBMITTED, COMPLETED)).isFalse();   // completion before a plan
    assertThat(RequestLifecycle.canTransition(RECEIVED, PLAN_PROPOSED)).isFalse(); // plan before submission
    assertThat(RequestLifecycle.canTransition(PLAN_AUTHORIZED, PLAN_PROPOSED)).isFalse(); // replayed proposal
    assertThatThrownBy(() -> RequestLifecycle.transition(COMPLETED, FAILED)).isInstanceOf(RequestLifecycle.IllegalTransitionException.class);
  }

  @Test
  void approvalsAndErrorsHaveRecoveryPaths() {
    assertThat(RequestLifecycle.canTransition(PENDING_APPROVAL_STAGE2, PLAN_AUTHORIZED)).isTrue();
    assertThat(RequestLifecycle.canTransition(PENDING_APPROVAL_STAGE2, STALE_PLAN)).isTrue();
    assertThat(RequestLifecycle.canTransition(PENDING_APPROVAL_STAGE1, APPROVAL_EXPIRED)).isTrue();
    assertThat(RequestLifecycle.canTransition(EVALUATION_ERROR, SUBMITTED)).isTrue();
    assertThat(RequestLifecycle.canTransition(EVALUATION_ERROR, PLAN_AUTHORIZED)).isTrue();
  }
}

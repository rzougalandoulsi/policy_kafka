package com.itland.governance.application.approvals;

import com.itland.governance.application.approvals.workflow.ApprovalReleaseSteps;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * TTL sweep for approvals with {@code engine = LEGACY} only (the query excludes Flowable rows, whose deadline is a
 * boundary timer). Kept until the legacy approvals have drained, then removed (ADR 0011).
 */
@Component
public class ApprovalExpiryJob {

  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ApprovalReleaseSteps steps;
  private final Blocking blocking;
  private final Clock clock;

  public ApprovalExpiryJob(ApprovalRepository approvals, ManifestRequestRepository requests, ApprovalReleaseSteps steps, Blocking blocking, Clock clock) {
    this.approvals = approvals;
    this.requests = requests;
    this.steps = steps;
    this.blocking = blocking;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${governance.workflow.approval-expiry-interval:60s}", initialDelayString = "30s")
  public void expire() {
    List<ApprovalRequest> expired = blocking.transactional(() -> approvals.findExpired(clock.instant(), 100));
    for (ApprovalRequest a : expired) {
      blocking.transactionalVoid(() -> expireOne(a));
    }
  }

  void expireOne(ApprovalRequest a) {
    if (!approvals.resolve(a.id(), a.version(), ApprovalStatus.EXPIRED, "Expired at " + a.expiresAt(), clock.instant())) {
      return; // resolved concurrently
    }
    ApprovalRequest expired = approvals.findById(a.id()).orElseThrow();
    ManifestRequest request = requests.findById(a.requestId()).orElseThrow();
    steps.expire(expired, request);
  }
}

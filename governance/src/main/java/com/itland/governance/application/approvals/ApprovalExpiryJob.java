package com.itland.governance.application.approvals;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.usage.QuotaReservationService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.RequestLifecycle;
import com.itland.governance.domain.request.RequestStatus;
import com.itland.governance.infrastructure.kafka.ManifestCommandPublisher;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Expires stale approvals and tells the Orchestrator to drop plans nobody approved in time. */
@Component
public class ApprovalExpiryJob {

  private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryJob.class);

  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ManifestCommandPublisher publisher;
  private final QuotaReservationService reservations;
  private final AuditService audit;
  private final Blocking blocking;
  private final Clock clock;

  public ApprovalExpiryJob(ApprovalRepository approvals, ManifestRequestRepository requests, ManifestCommandPublisher publisher,
                           QuotaReservationService reservations, AuditService audit, Blocking blocking, Clock clock) {
    this.approvals = approvals;
    this.requests = requests;
    this.publisher = publisher;
    this.reservations = reservations;
    this.audit = audit;
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
    ManifestRequest request = requests.findById(a.requestId()).orElseThrow();
    if (RequestLifecycle.canTransition(request.status(), RequestStatus.APPROVAL_EXPIRED)) {
      requests.transition(request.id(), request.version(), request.status(), RequestStatus.APPROVAL_EXPIRED, "Approval " + a.id() + " expired", null, null, null, null, clock.instant());
      if (a.stage() == GovernanceStage.ACTION_PLAN) {
        publisher.enqueueReject(request, a.planId(), a.planDigest(), a.decisionId(), "APPROVAL_EXPIRED", List.of());
      }
    }
    reservations.release(request.id(), "APPROVAL_EXPIRED");
    audit.record("system", "APPROVAL_EXPIRED", "approval_request", a.id(), a.requestId(), Map.of("stage", a.stage().name()));
    log.info("Approval {} for request {} expired", a.id(), a.requestId());
  }
}

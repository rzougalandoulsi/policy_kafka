package com.itland.governance.application.approvals;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.policy.NotFoundException;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.approval.ApprovalDecision;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalRules;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.approval.ApprovalVerdict;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.security.AccessDeniedException;
import com.itland.governance.infrastructure.security.CallerContext;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Read side of approvals (list, get, team authorisation) plus the retained legacy resolution (quorum computed in
 * Java, release through {@link ApprovalReleaseService}) for approvals opened with {@code engine = LEGACY}. New
 * approvals are opened and decided by {@code ApprovalWorkflowService} on the embedded Flowable engine (ADR 0011); the
 * legacy branch is deleted once {@code governance_approvals_legacy_pending} has reached zero everywhere.
 */
@Service
public class ApprovalService {

  private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

  public record Detail(ApprovalRequest approval, List<ApprovalDecision> decisions, ManifestRequest request) {}

  public static class ApprovalRuleException extends RuntimeException {
    public ApprovalRuleException(String message) { super(message); }
  }

  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ApprovalReleaseService release;
  private final TeamAuthorization authorization;
  private final AuditService audit;
  private final GovernanceMetrics metrics;
  private final Blocking blocking;
  private final Clock clock;

  public ApprovalService(ApprovalRepository approvals, ManifestRequestRepository requests, ApprovalReleaseService release,
                         TeamAuthorization authorization, AuditService audit, GovernanceMetrics metrics, Blocking blocking, Clock clock) {
    this.approvals = approvals;
    this.requests = requests;
    this.release = release;
    this.authorization = authorization;
    this.audit = audit;
    this.metrics = metrics;
    this.blocking = blocking;
    this.clock = clock;
  }

  public Mono<List<ApprovalRequest>> list(CallerContext caller, ApprovalStatus status, String teamId, int limit) {
    return blocking.call(() -> {
      boolean privileged = authorization.isApprover(caller) || authorization.isAuditor(caller);
      if (!privileged) {
        if (teamId == null || !caller.memberOf(teamId)) {
          throw new AccessDeniedException("Specify one of your teams with ?teamId= to list its approvals");
        }
      }
      return approvals.list(status, teamId, Math.min(Math.max(limit, 1), 500));
    });
  }

  public Mono<Detail> get(CallerContext caller, String approvalId) {
    return blocking.call(() -> {
      ApprovalRequest approval = approvals.findById(approvalId).orElseThrow(() -> new NotFoundException("Approval " + approvalId + " not found"));
      ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();
      authorization.requireView(caller, request.principalId(), request.teamId());
      return new Detail(approval, approvals.decisions(approvalId), request);
    });
  }

  /**
   * Legacy resolution after a verdict was recorded (row lock held by the caller): quorum in Java, then release or
   * rejection. Only reached for approvals with {@code engine = LEGACY}.
   */
  public Detail resolve(ApprovalRequest approval, ManifestRequest request, List<ApprovalDecision> all, CallerContext approver,
                        ApprovalVerdict verdict, String comment) {
    ApprovalStatus outcome = ApprovalRules.outcome(approval, all);
    if (outcome == ApprovalStatus.PENDING) {
      return new Detail(approval, all, request);
    }
    if (!approvals.resolve(approval.id(), approval.version(), outcome, verdict == ApprovalVerdict.REJECT ? "Rejected by " + approver.principalId() : "Quorum reached", clock.instant())) {
      throw new IllegalStateException("Concurrent modification of approval " + approval.id());
    }
    metrics.recordApprovalResolved(approval.stage().name(), outcome.name(), approval.engine().name());
    ApprovalRequest resolved = approvals.findById(approval.id()).orElseThrow();
    if (outcome == ApprovalStatus.REJECTED) {
      release.rejectAfterApproval(resolved, request, approver.principalId(), comment);
    } else {
      release.releaseAfterApproval(resolved, request, approver.principalId());
    }
    log.info("Approval {} for request {} resolved as {}", approval.id(), request.id(), outcome);
    return new Detail(approvals.findById(approval.id()).orElseThrow(), all, requests.findById(request.id()).orElseThrow());
  }

  /** Cancels one pending approval without touching any process instance. Caller's transaction. */
  public void cancelOne(ApprovalRequest a, String reason) {
    if (approvals.resolve(a.id(), a.version(), ApprovalStatus.CANCELLED, reason, clock.instant())) {
      metrics.recordApprovalResolved(a.stage().name(), ApprovalStatus.CANCELLED.name(), a.engine().name());
      audit.record("system", "APPROVAL_CANCELLED", "approval_request", a.id(), a.requestId(), Map.of("reason", reason));
    }
  }
}

package com.itland.governance.application.approvals;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.policy.NotFoundException;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.approval.ApprovalDecision;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalRules;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.approval.ApprovalVerdict;
import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.policy.ApprovalConfiguration;
import com.itland.governance.domain.request.ManifestRequest;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable human approvals bound to an immutable digest (manifest digest for stage 1, plan digest for stage 2).
 * Decisions are idempotent per approver, protected by a row lock, and audited. Nothing here waits on a Kafka thread.
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
  private final Blocking blocking;
  private final Clock clock;

  public ApprovalService(ApprovalRepository approvals, ManifestRequestRepository requests, ApprovalReleaseService release,
                         TeamAuthorization authorization, AuditService audit, Blocking blocking, Clock clock) {
    this.approvals = approvals;
    this.requests = requests;
    this.release = release;
    this.authorization = authorization;
    this.audit = audit;
    this.blocking = blocking;
    this.clock = clock;
  }

  /** Opens a PENDING approval inside the caller's transaction. */
  public ApprovalRequest open(ManifestRequest request, GovernanceDecision decision, GovernanceStage stage, List<ApprovalRequirement> requirements,
                              String digest, String planId, String planDigest) {
    ApprovalConfiguration config = approvals.findActiveConfiguration().orElseThrow(() -> new IllegalStateException("No ACTIVE approval configuration"));
    Instant now = clock.instant();
    ApprovalRequest approval = new ApprovalRequest("apr-" + UUID.randomUUID(), request.id(), stage, decision.id(), planId, planDigest, digest,
        ApprovalStatus.PENDING, request.principalId(), config.requiredApprovals(), config.approverRoles(), config.separationOfDuties(),
        requirements, now.plus(Duration.ofHours(config.ttlHours())), 0, now, null, null);
    approvals.insert(approval);
    audit.record("system", "APPROVAL_OPENED", "approval_request", approval.id(), request.id(),
        Map.of("stage", stage.name(), "decisionId", decision.id(), "requiredApprovals", config.requiredApprovals(), "expiresAt", approval.expiresAt().toString()));
    return approval;
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

  /** Records a verdict; when quorum is reached the plan/manifest is revalidated and released, when rejected the request ends. */
  public Mono<Detail> decide(CallerContext approver, String approvalId, ApprovalVerdict verdict, String comment) {
    authorization.requireApprover(approver);
    return blocking.inTransaction(() -> {
      ApprovalRequest approval = approvals.lockById(approvalId).orElseThrow(() -> new NotFoundException("Approval " + approvalId + " not found"));
      ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();
      Instant now = clock.instant();
      List<ApprovalDecision> existing = approvals.decisions(approvalId);

      Optional<ApprovalDecision> mine = existing.stream().filter(d -> d.approverId().equals(approver.principalId())).findFirst();
      if (mine.isPresent()) {
        if (mine.get().verdict() != verdict) {
          throw new ApprovalRuleException("You already recorded " + mine.get().verdict() + " on this approval");
        }
        return new Detail(approval, existing, request); // idempotent replay
      }
      ApprovalRules.eligibility(approval, approver, existing, now).ifPresent(reason -> { throw new ApprovalRuleException(reason); });

      ApprovalDecision decision = new ApprovalDecision("apd-" + UUID.randomUUID(), approvalId, approver.principalId(), verdict, comment, now);
      approvals.insertDecision(decision);
      List<ApprovalDecision> all = approvals.decisions(approvalId);
      audit.record(approver.principalId(), "APPROVAL_DECISION", "approval_request", approvalId, request.id(),
          Map.of("verdict", verdict.name(), "comment", comment == null ? "" : comment));

      ApprovalStatus outcome = ApprovalRules.outcome(approval, all);
      if (outcome == ApprovalStatus.PENDING) {
        return new Detail(approval, all, request);
      }
      if (!approvals.resolve(approvalId, approval.version(), outcome, verdict == ApprovalVerdict.REJECT ? "Rejected by " + approver.principalId() : "Quorum reached", now)) {
        throw new IllegalStateException("Concurrent modification of approval " + approvalId);
      }
      if (outcome == ApprovalStatus.REJECTED) {
        release.rejectAfterApproval(approval, request, approver.principalId(), comment);
      } else {
        release.releaseAfterApproval(approval, request, approver.principalId());
      }
      log.info("Approval {} for request {} resolved as {}", approvalId, request.id(), outcome);
      return new Detail(approvals.findById(approvalId).orElseThrow(), all, requests.findById(request.id()).orElseThrow());
    });
  }

  /** Cancels pending approvals when the request ends by other means (orchestrator rejection, completion). Caller's transaction. */
  public void cancelPending(String requestId, String reason) {
    for (ApprovalRequest a : approvals.findPendingByRequest(requestId)) {
      if (approvals.resolve(a.id(), a.version(), ApprovalStatus.CANCELLED, reason, clock.instant())) {
        audit.record("system", "APPROVAL_CANCELLED", "approval_request", a.id(), requestId, Map.of("reason", reason));
      }
    }
  }
}

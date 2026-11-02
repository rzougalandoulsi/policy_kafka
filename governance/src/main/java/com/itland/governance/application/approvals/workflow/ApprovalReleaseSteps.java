package com.itland.governance.application.approvals.workflow;

import com.itland.governance.application.approvals.ApprovalService;
import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.evaluation.PolicyEvaluationService;
import com.itland.governance.application.usage.QuotaReservationService;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.request.RequestLifecycle;
import com.itland.governance.domain.request.RequestStatus;
import com.itland.governance.infrastructure.kafka.ManifestCommandPublisher;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.DecisionRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The release steps that follow a human outcome, shared by the BPMN service tasks (authoritative Flowable path) and
 * by the retained legacy {@code ApprovalReleaseService} / {@code ApprovalExpiryJob} until the last legacy approval has
 * drained. Every step runs inside the caller's transaction: revalidation calls OPA (the only policy decision point),
 * transitions go through {@code RequestLifecycle}, Kafka commands are outbox rows, and each step is audited with the
 * same action names as before the migration.
 */
@Component
public class ApprovalReleaseSteps {

  private static final Logger log = LoggerFactory.getLogger(ApprovalReleaseSteps.class);
  private static final Duration EVALUATION_TIMEOUT = Duration.ofSeconds(30);

  /** Result of the stage 2 checks: {@code decision} is null when the plan is stale against {@code latestStateVersion}. */
  public record Stage2Revalidation(ProposedPlan plan, GovernanceDecision decision, Long latestStateVersion) {
    public boolean stale() { return decision == null; }
  }

  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final DecisionRepository decisions;
  private final ProposedPlanRepository plans;
  private final PolicyEvaluationService evaluation;
  private final ManifestCommandPublisher publisher;
  private final QuotaReservationService reservations;
  private final AuditService audit;
  private final GovernanceMetrics metrics;
  private final Clock clock;

  public ApprovalReleaseSteps(ApprovalRepository approvals, ManifestRequestRepository requests, DecisionRepository decisions,
                              ProposedPlanRepository plans, PolicyEvaluationService evaluation, ManifestCommandPublisher publisher,
                              QuotaReservationService reservations, AuditService audit, GovernanceMetrics metrics, Clock clock) {
    this.approvals = approvals;
    this.requests = requests;
    this.decisions = decisions;
    this.plans = plans;
    this.evaluation = evaluation;
    this.publisher = publisher;
    this.reservations = reservations;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Projects the engine outcome onto {@code approval_request} (the public read model). Optimistic; must hold the row. */
  public ApprovalRequest resolveApproval(ApprovalRequest approval, ApprovalStatus status, String reason) {
    if (!approvals.resolve(approval.id(), approval.version(), status, reason, clock.instant())) {
      throw new IllegalStateException("Concurrent modification of approval " + approval.id());
    }
    metrics.recordApprovalResolved(approval.stage().name(), status.name(), approval.engine().name());
    log.info("Approval {} for request {} resolved as {} ({})", approval.id(), approval.requestId(), status, reason);
    return approvals.findById(approval.id()).orElseThrow();
  }

  // ---- stage 1 ----

  /** Digest check and OPA re-evaluation of the manifest; the decision is persisted whatever its type. */
  public GovernanceDecision revalidateStage1(ApprovalRequest approval, ManifestRequest request) {
    if (!approval.digest().equals(request.manifestDigest())) {
      throw new IllegalStateException("Approval digest does not match the stored manifest");
    }
    GovernanceDecision decision = evaluate(GovernanceStage.REQUEST, request, null);
    decisions.insert(decision);
    return decision;
  }

  public void submitManifest(ApprovalRequest approval, ManifestRequest request, GovernanceDecision decision, String actor) {
    transition(request, RequestStatus.SUBMITTED, "Approved by quorum; submitted to orchestrator");
    String outboxId = publisher.enqueueSubmit(request);
    auditStage1(actor, approval, request, decision, outboxId);
  }

  public void denyStage1(ApprovalRequest approval, ManifestRequest request, GovernanceDecision decision, String actor) {
    transition(request, RequestStatus.STAGE1_DENIED, "Denied on re-evaluation after approval: " + policyIds(decision));
    auditStage1(actor, approval, request, decision, null);
  }

  private void auditStage1(String actor, ApprovalRequest approval, ManifestRequest request, GovernanceDecision decision, String outboxId) {
    audit.record(actor, "APPROVAL_RELEASE_STAGE1", "manifest_request", request.id(), request.id(),
        details(approval, decision.id(), decision.type().name(), null, outboxId));
  }

  // ---- stage 2 ----

  /**
   * Plan identity, freshness against the latest confirmed state version, then OPA re-evaluation under the team's row
   * lock (ADR 0010). Returns a stale result without evaluating when the repository moved on.
   */
  public Stage2Revalidation revalidateStage2(ApprovalRequest approval, ManifestRequest request) {
    ProposedPlan plan = plans.findByPlanId(approval.planId()).orElseThrow(() -> new IllegalStateException("Approved plan no longer stored"));
    if (!plan.planDigest().equals(approval.planDigest()) || !plan.planId().equals(request.planId())) {
      throw new IllegalStateException("Approval does not refer to the request's current plan");
    }
    Optional<Long> latest = plans.latestStateVersion(request.teamId(), request.repositoryId(), request.environment());
    if (latest.isPresent() && latest.get() > plan.stateVersion()) {
      return new Stage2Revalidation(plan, null, latest.get());
    }
    // Quota admission under the team's row lock: concurrent approvals of one team are serialised here and the second
    // re-evaluation sees the first plan's committed reservation.
    reservations.lockTeam(request.teamId());
    GovernanceDecision decision = evaluate(GovernanceStage.ACTION_PLAN, request, plan);
    decisions.insert(decision);
    return new Stage2Revalidation(plan, decision, latest.orElse(null));
  }

  public void authorizePlan(ApprovalRequest approval, ManifestRequest request, ProposedPlan plan, GovernanceDecision decision, String actor) {
    transition(request, RequestStatus.PLAN_AUTHORIZED, "Approved by quorum; plan authorized");
    reservations.reserve(request, plan, decision.id());
    String outboxId = publisher.enqueueAuthorize(request, plan, decision.id());
    auditStage2(actor, approval, request, plan, decision, outboxId);
  }

  public void denyStage2(ApprovalRequest approval, ManifestRequest request, ProposedPlan plan, GovernanceDecision decision, String actor) {
    transition(request, RequestStatus.PLAN_REJECTED, "Denied on re-evaluation after approval: " + policyIds(decision));
    String outboxId = publisher.enqueueReject(request, plan.planId(), plan.planDigest(), decision.id(), "POLICY_DENIED", decision.blockingViolations());
    auditStage2(actor, approval, request, plan, decision, outboxId);
  }

  public void stalePlan(ApprovalRequest approval, ManifestRequest request, ProposedPlan plan, long latestStateVersion, String actor) {
    String reason = "Plan computed against state version " + plan.stateVersion() + " but repository is at " + latestStateVersion + "; resubmit to replan";
    transition(request, RequestStatus.STALE_PLAN, reason);
    String outboxId = publisher.enqueueReject(request, plan.planId(), plan.planDigest(), approval.decisionId(), "STALE_PLAN", List.of());
    audit.record(actor, "APPROVAL_RELEASE_STALE", "manifest_request", request.id(), request.id(),
        details(approval, approval.decisionId(), "STALE", plan.planId(), outboxId));
  }

  private void auditStage2(String actor, ApprovalRequest approval, ManifestRequest request, ProposedPlan plan, GovernanceDecision decision, String outboxId) {
    audit.record(actor, "APPROVAL_RELEASE_STAGE2", "manifest_request", request.id(), request.id(),
        details(approval, decision.id(), decision.type().name(), plan.planId(), outboxId));
  }

  // ---- human rejection, expiry ----

  /** A REJECT verdict: STAGE1_DENIED or PLAN_REJECTED (+ REJECT_PLAN for stage 2). */
  public void rejectAfterVerdict(ApprovalRequest approval, ManifestRequest request, String actor, String comment) {
    RequestStatus target = approval.stage() == GovernanceStage.REQUEST ? RequestStatus.STAGE1_DENIED : RequestStatus.PLAN_REJECTED;
    String reason = "Rejected by approver " + actor + (comment == null || comment.isBlank() ? "" : ": " + comment);
    String outboxId = null;
    if (RequestLifecycle.canTransition(request.status(), target)) {
      transition(request, target, reason);
      if (approval.stage() == GovernanceStage.ACTION_PLAN) {
        outboxId = publisher.enqueueReject(request, approval.planId(), approval.planDigest(), approval.decisionId(), "APPROVAL_REJECTED", List.of());
      }
    }
    audit.record(actor, "APPROVAL_REJECTED_RELEASE", "manifest_request", request.id(), request.id(), details(approval, null, null, null, outboxId));
  }

  /** Deadline passed: APPROVAL_EXPIRED, REJECT_PLAN for stage 2, reservations released. The approval row is already EXPIRED. */
  public void expire(ApprovalRequest approval, ManifestRequest request) {
    String outboxId = null;
    if (RequestLifecycle.canTransition(request.status(), RequestStatus.APPROVAL_EXPIRED)) {
      transition(request, RequestStatus.APPROVAL_EXPIRED, "Approval " + approval.id() + " expired");
      if (approval.stage() == GovernanceStage.ACTION_PLAN) {
        outboxId = publisher.enqueueReject(request, approval.planId(), approval.planDigest(), approval.decisionId(), "APPROVAL_EXPIRED", List.of());
      }
    }
    reservations.release(request.id(), "APPROVAL_EXPIRED");
    audit.record("system", "APPROVAL_EXPIRED", "approval_request", approval.id(), approval.requestId(),
        details(approval, null, null, null, outboxId));
    log.info("Approval {} for request {} expired", approval.id(), approval.requestId());
  }

  /** OPA could not answer: the caller rolls the verdict back and the approver retries. */
  public static ApprovalService.ApprovalRuleException unavailable(GovernanceDecision decision) {
    return new ApprovalService.ApprovalRuleException("Policy re-evaluation unavailable, try again: " + decision.errorMessage());
  }

  public GovernanceDecision decision(String id) {
    return decisions.findById(id).orElseThrow(() -> new IllegalStateException("Revalidation decision " + id + " not stored"));
  }

  private GovernanceDecision evaluate(GovernanceStage stage, ManifestRequest request, ProposedPlan plan) {
    GovernanceDecision d = evaluation.evaluate(stage, request, Set.of(), plan).block(EVALUATION_TIMEOUT);
    if (d == null) throw new IllegalStateException("Evaluation produced no decision");
    if (d.type() == DecisionType.REQUIRE_APPROVAL) {
      // The approval just granted covers the requirement; a second approval round is never opened from a release.
      log.info("Re-evaluation of request {} still requires approval; released on the recorded quorum", request.id());
    }
    return d;
  }

  private void transition(ManifestRequest request, RequestStatus to, String reason) {
    ManifestRequest fresh = requests.findById(request.id()).orElseThrow();
    if (!RequestLifecycle.canTransition(fresh.status(), to)) {
      throw new IllegalStateException("Request " + request.id() + " is " + fresh.status() + "; cannot move to " + to);
    }
    if (!requests.transition(fresh.id(), fresh.version(), fresh.status(), to, reason, null, null, null, null, clock.instant())) {
      throw new IllegalStateException("Concurrent modification of request " + request.id());
    }
    log.info("Request {} {} -> {} ({})", request.id(), fresh.status(), to, reason);
  }

  private static Map<String, Object> details(ApprovalRequest approval, String decisionId, String result, String planId, String outboxId) {
    Map<String, Object> d = new java.util.LinkedHashMap<>();
    d.put("approvalId", approval.id());
    d.put("engine", approval.engine().name());
    if (approval.processInstanceId() != null) d.put("processInstanceId", approval.processInstanceId());
    if (decisionId != null) d.put("decisionId", decisionId);
    if (result != null) d.put("result", result);
    if (planId != null) d.put("planId", planId);
    if (outboxId != null) d.put("outboxId", outboxId);
    return d;
  }

  private static List<String> policyIds(GovernanceDecision d) {
    return d.blockingViolations().stream().map(v -> v.policyId()).distinct().toList();
  }
}

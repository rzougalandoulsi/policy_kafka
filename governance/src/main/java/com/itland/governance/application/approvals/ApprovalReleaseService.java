package com.itland.governance.application.approvals;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.evaluation.PolicyEvaluationService;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.request.RequestLifecycle;
import com.itland.governance.domain.request.RequestStatus;
import com.itland.governance.infrastructure.kafka.ManifestCommandPublisher;
import com.itland.governance.infrastructure.persistence.DecisionRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Completes an approval: revalidates policies and exceptions, checks the plan is still the one approved and still
 * fresh against the latest confirmed-state version, then releases (SUBMIT_MANIFEST for stage 1, AUTHORIZE_PLAN for
 * stage 2) or rejects. Runs synchronously inside the approval transaction on a worker thread.
 */
@Service
public class ApprovalReleaseService {

  private static final Logger log = LoggerFactory.getLogger(ApprovalReleaseService.class);
  private static final Duration EVALUATION_TIMEOUT = Duration.ofSeconds(30);

  private final ManifestRequestRepository requests;
  private final DecisionRepository decisions;
  private final ProposedPlanRepository plans;
  private final PolicyEvaluationService evaluation;
  private final ManifestCommandPublisher publisher;
  private final AuditService audit;
  private final Clock clock;

  public ApprovalReleaseService(ManifestRequestRepository requests, DecisionRepository decisions, ProposedPlanRepository plans,
                                PolicyEvaluationService evaluation, ManifestCommandPublisher publisher, AuditService audit, Clock clock) {
    this.requests = requests;
    this.decisions = decisions;
    this.plans = plans;
    this.evaluation = evaluation;
    this.publisher = publisher;
    this.audit = audit;
    this.clock = clock;
  }

  public void releaseAfterApproval(ApprovalRequest approval, ManifestRequest request, String actor) {
    if (approval.stage() == GovernanceStage.REQUEST) {
      releaseStage1(approval, request, actor);
    } else {
      releaseStage2(approval, request, actor);
    }
  }

  public void rejectAfterApproval(ApprovalRequest approval, ManifestRequest request, String actor, String comment) {
    RequestStatus target = approval.stage() == GovernanceStage.REQUEST ? RequestStatus.STAGE1_DENIED : RequestStatus.PLAN_REJECTED;
    String reason = "Rejected by approver " + actor + (comment == null ? "" : ": " + comment);
    if (RequestLifecycle.canTransition(request.status(), target)) {
      transition(request, target, reason);
      if (approval.stage() == GovernanceStage.ACTION_PLAN) {
        publisher.enqueueReject(request, approval.planId(), approval.planDigest(), approval.decisionId(), "APPROVAL_REJECTED", List.of());
      }
    }
    audit.record(actor, "APPROVAL_REJECTED_RELEASE", "manifest_request", request.id(), request.id(), Map.of("approvalId", approval.id()));
  }

  private void releaseStage1(ApprovalRequest approval, ManifestRequest request, String actor) {
    if (!approval.digest().equals(request.manifestDigest())) {
      throw new IllegalStateException("Approval digest does not match the stored manifest");
    }
    GovernanceDecision decision = evaluate(GovernanceStage.REQUEST, request, null);
    decisions.insert(decision);
    switch (decision.type()) {
      case ERROR -> throw new ApprovalService.ApprovalRuleException("Policy re-evaluation unavailable, try again: " + decision.errorMessage());
      case DENY -> transition(request, RequestStatus.STAGE1_DENIED, "Denied on re-evaluation after approval: " + policyIds(decision));
      default -> {
        transition(request, RequestStatus.SUBMITTED, "Approved by quorum; submitted to orchestrator");
        publisher.enqueueSubmit(request);
      }
    }
    audit.record(actor, "APPROVAL_RELEASE_STAGE1", "manifest_request", request.id(), request.id(),
        Map.of("approvalId", approval.id(), "decisionId", decision.id(), "result", decision.type().name()));
  }

  private void releaseStage2(ApprovalRequest approval, ManifestRequest request, String actor) {
    ProposedPlan plan = plans.findByPlanId(approval.planId()).orElseThrow(() -> new IllegalStateException("Approved plan no longer stored"));
    if (!plan.planDigest().equals(approval.planDigest()) || !plan.planId().equals(request.planId())) {
      throw new IllegalStateException("Approval does not refer to the request's current plan");
    }
    Optional<Long> latest = plans.latestStateVersion(request.teamId(), request.repositoryId(), request.environment());
    if (latest.isPresent() && latest.get() > plan.stateVersion()) {
      String reason = "Plan computed against state version " + plan.stateVersion() + " but repository is at " + latest.get() + "; resubmit to replan";
      transition(request, RequestStatus.STALE_PLAN, reason);
      publisher.enqueueReject(request, plan.planId(), plan.planDigest(), approval.decisionId(), "STALE_PLAN", List.of());
      audit.record(actor, "APPROVAL_RELEASE_STALE", "manifest_request", request.id(), request.id(), Map.of("approvalId", approval.id()));
      return;
    }
    GovernanceDecision decision = evaluate(GovernanceStage.ACTION_PLAN, request, plan);
    decisions.insert(decision);
    switch (decision.type()) {
      case ERROR -> throw new ApprovalService.ApprovalRuleException("Policy re-evaluation unavailable, try again: " + decision.errorMessage());
      case DENY -> {
        transition(request, RequestStatus.PLAN_REJECTED, "Denied on re-evaluation after approval: " + policyIds(decision));
        publisher.enqueueReject(request, plan.planId(), plan.planDigest(), decision.id(), "POLICY_DENIED", decision.blockingViolations());
      }
      default -> {
        transition(request, RequestStatus.PLAN_AUTHORIZED, "Approved by quorum; plan authorized");
        publisher.enqueueAuthorize(request, plan, decision.id());
      }
    }
    audit.record(actor, "APPROVAL_RELEASE_STAGE2", "manifest_request", request.id(), request.id(),
        Map.of("approvalId", approval.id(), "decisionId", decision.id(), "planId", plan.planId(), "result", decision.type().name()));
  }

  private GovernanceDecision evaluate(GovernanceStage stage, ManifestRequest request, ProposedPlan plan) {
    GovernanceDecision d = evaluation.evaluate(stage, request, Set.of(), plan).block(EVALUATION_TIMEOUT);
    if (d == null) throw new IllegalStateException("Evaluation produced no decision");
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

  private static List<String> policyIds(GovernanceDecision d) {
    return d.blockingViolations().stream().map(v -> v.policyId()).distinct().toList();
  }
}

package com.itland.governance.application.lifecycle;

import com.itland.governance.application.approvals.ApprovalService;
import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.evaluation.PolicyEvaluationService;
import com.itland.governance.application.usage.QuotaReservationService;
import com.itland.governance.application.usage.UsageMath;
import com.itland.governance.infrastructure.persistence.RepositoryUsageRepository;
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
import com.itland.platform.avro.ManifestCompletedPayload;
import com.itland.platform.avro.ManifestEvent;
import com.itland.platform.avro.ManifestFailedPayload;
import com.itland.platform.avro.ManifestRejectedPayload;
import com.itland.platform.avro.PlanProposedPayload;
import com.itland.platform.avro.PlannedAction;
import com.itland.platform.contracts.Digests;
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
 * Applies Orchestrator events to the request lifecycle. Runs inside the inbox transaction on a worker thread.
 * PLAN_PROPOSED triggers Stage 2 evaluation; terminal events close the request. Anything that does not fit the
 * current state (replays, late or out-of-order events) is ignored and recorded, never applied.
 */
@Service
public class ManifestEventHandler {

  private static final Logger log = LoggerFactory.getLogger(ManifestEventHandler.class);
  private static final Duration EVALUATION_TIMEOUT = Duration.ofSeconds(30);

  public record Outcome(String code, boolean ignored) {
    static Outcome ok(String code) { return new Outcome(code, false); }
    static Outcome ignored(String code) { return new Outcome(code, true); }
  }

  /** Transient failure: the inbox entry will be retried with backoff. */
  public static class RetryableException extends RuntimeException {
    public RetryableException(String message) { super(message); }
  }

  private final ManifestRequestRepository requests;
  private final ProposedPlanRepository plans;
  private final DecisionRepository decisions;
  private final PolicyEvaluationService evaluation;
  private final ApprovalService approvals;
  private final ManifestCommandPublisher publisher;
  private final QuotaReservationService reservations;
  private final RepositoryUsageRepository usage;
  private final AuditService audit;
  private final Clock clock;

  public ManifestEventHandler(ManifestRequestRepository requests, ProposedPlanRepository plans, DecisionRepository decisions,
                              PolicyEvaluationService evaluation, ApprovalService approvals, ManifestCommandPublisher publisher,
                              QuotaReservationService reservations, RepositoryUsageRepository usage, AuditService audit, Clock clock) {
    this.requests = requests;
    this.plans = plans;
    this.decisions = decisions;
    this.evaluation = evaluation;
    this.approvals = approvals;
    this.publisher = publisher;
    this.reservations = reservations;
    this.usage = usage;
    this.audit = audit;
    this.clock = clock;
  }

  public Outcome handle(ManifestEvent event) {
    Optional<ManifestRequest> maybe = requests.findById(event.getRequestId());
    if (maybe.isEmpty()) {
      log.warn("Event {} refers to unknown request {}", event.getEventType(), event.getRequestId());
      if (event.getPayload() instanceof PlanProposedPayload) {
        // Safety net (e.g. legacy direct ingress): never leave an ungoverned plan pending in the Orchestrator.
        ManifestRequest ghost = new ManifestRequest(event.getRequestId(), "unknown", "", event.getTeamId(), event.getRepositoryId(), event.getEnvironment(),
            null, "", RequestStatus.RECEIVED, event.getPlanId(), event.getPlanDigest(), event.getStateVersion(), null, 0, clock.instant(), clock.instant(), null);
        publisher.enqueueReject(ghost, event.getPlanId(), event.getPlanDigest(), null, "UNKNOWN_REQUEST", List.of());
      }
      return Outcome.ignored("UNKNOWN_REQUEST");
    }
    ManifestRequest request = maybe.get();
    if (event.getStateVersion() != null && !(event.getPayload() instanceof PlanProposedPayload)) {
      plans.recordStateVersion(request.teamId(), request.repositoryId(), request.environment(), event.getStateVersion(), clock.instant());
    }
    return switch (event.getEventType()) {
      case PLAN_PROPOSED -> onPlanProposed(request, event, (PlanProposedPayload) event.getPayload());
      case MANIFEST_COMPLETED -> terminal(request, event, RequestStatus.COMPLETED, "Executed: " + ((ManifestCompletedPayload) event.getPayload()).getResults().size() + " actions");
      case MANIFEST_FAILED -> terminal(request, event, RequestStatus.FAILED, ((ManifestFailedPayload) event.getPayload()).getReasonCode() + ": " + ((ManifestFailedPayload) event.getPayload()).getMessage());
      case MANIFEST_REJECTED -> {
        ManifestRejectedPayload p = (ManifestRejectedPayload) event.getPayload();
        RequestStatus target = "STALE_PLAN".equals(p.getReasonCode()) ? RequestStatus.STALE_PLAN : RequestStatus.REJECTED_BY_ORCHESTRATOR;
        yield terminal(request, event, target, p.getReasonCode() + ": " + p.getMessage());
      }
    };
  }

  /** Fail-closed fallback once retries are exhausted: make sure the Orchestrator drops the plan. */
  public void onExhausted(ManifestEvent event, String error) {
    requests.findById(event.getRequestId()).ifPresent(request -> {
      if (event.getPayload() instanceof PlanProposedPayload && !request.status().terminal()) {
        publisher.enqueueReject(request, event.getPlanId(), event.getPlanDigest(), null, "EVALUATION_ERROR", List.of());
        audit.record("system", "STAGE2_EVALUATION_EXHAUSTED", "manifest_request", request.id(), request.id(), Map.of("error", error == null ? "" : error));
      }
    });
  }

  private Outcome onPlanProposed(ManifestRequest request, ManifestEvent event, PlanProposedPayload payload) {
    if (request.status() == RequestStatus.PLAN_PROPOSED || request.status() == RequestStatus.PENDING_APPROVAL_STAGE2 || request.status() == RequestStatus.PLAN_AUTHORIZED) {
      if (event.getPlanId().equals(request.planId())) {
        return Outcome.ignored("DUPLICATE_PLAN");
      }
    }
    if (!RequestLifecycle.canTransition(request.status(), RequestStatus.PLAN_PROPOSED)) {
      log.warn("PLAN_PROPOSED for request {} in state {} ignored (out of order)", request.id(), request.status());
      if (request.status().terminal()) {
        publisher.enqueueReject(request, event.getPlanId(), event.getPlanDigest(), null, "REQUEST_CLOSED", List.of());
      }
      return Outcome.ignored("OUT_OF_ORDER");
    }
    List<PlannedAction> actions = payload.getActions();
    String expectedDigest = Digests.planDigest(actions, payload.getManifestDigest(), event.getStateVersion() == null ? 0 : event.getStateVersion());
    if (!payload.getManifestDigest().equals(request.manifestDigest()) || !expectedDigest.equals(event.getPlanDigest())) {
      log.error("Plan {} for request {} fails integrity checks (manifest or plan digest mismatch)", event.getPlanId(), request.id());
      transition(request, RequestStatus.PLAN_REJECTED, "Plan integrity check failed", event.getEventId(), event.getPlanId(), event.getPlanDigest(), event.getStateVersion());
      publisher.enqueueReject(request, event.getPlanId(), event.getPlanDigest(), null, "DIGEST_MISMATCH", List.of());
      return Outcome.ok("INTEGRITY_REJECTED");
    }
    ProposedPlan plan = new ProposedPlan(event.getPlanId(), request.id(), event.getPlanDigest(), payload.getManifestDigest(),
        event.getStateVersion() == null ? 0 : event.getStateVersion(),
        actions.stream().map(a -> new ProposedPlan.PlannedActionView(a.getActionId(), a.getResourceType(), a.getResourceName(), a.getActionType(), a.getPayload())).toList(),
        event.getEventId(), clock.instant());
    plans.upsert(plan);
    ManifestRequest proposed = transition(request, RequestStatus.PLAN_PROPOSED, "Plan proposed with " + actions.size() + " actions",
        event.getEventId(), plan.planId(), plan.planDigest(), plan.stateVersion());

    // Quota admission: hold the team's row lock (when it has a configuration) across the evaluation so the quota rule
    // sees every committed reservation, and reserve this plan's deltas in the same transaction as PLAN_AUTHORIZED.
    reservations.lockTeam(request.teamId());
    GovernanceDecision decision = evaluation.evaluate(GovernanceStage.ACTION_PLAN, proposed, Set.of(), plan).block(EVALUATION_TIMEOUT);
    if (decision == null) throw new RetryableException("Stage 2 evaluation produced no decision");
    decisions.insert(decision);
    audit.record("system", "STAGE2_DECISION", "manifest_request", request.id(), request.id(),
        Map.of("decisionId", decision.id(), "type", decision.type().name(), "planId", plan.planId(), "policyRevision", decision.policyRevision(),
            "shadowFindings", decision.shadowViolations().size() + decision.shadowApprovals().size()));

    switch (decision.type()) {
      case ALLOW -> {
        transition(proposed, RequestStatus.PLAN_AUTHORIZED, "Stage 2 allowed", event.getEventId(), null, null, null);
        reservations.reserve(proposed, plan, decision.id());
        publisher.enqueueAuthorize(proposed, plan, decision.id());
        return Outcome.ok("AUTHORIZED");
      }
      case DENY -> {
        transition(proposed, RequestStatus.PLAN_REJECTED, "Denied by policy: " + decision.blockingViolations().stream().map(v -> v.policyId()).distinct().toList(), event.getEventId(), null, null, null);
        publisher.enqueueReject(proposed, plan.planId(), plan.planDigest(), decision.id(), "POLICY_DENIED", decision.blockingViolations());
        return Outcome.ok("REJECTED");
      }
      case REQUIRE_APPROVAL -> {
        transition(proposed, RequestStatus.PENDING_APPROVAL_STAGE2, "Approval required: " + decision.enforcedApprovals().stream().map(a -> a.policyId()).distinct().toList(), event.getEventId(), null, null, null);
        approvals.open(proposed, decision, GovernanceStage.ACTION_PLAN, decision.enforcedApprovals(), plan.planDigest(), plan.planId(), plan.planDigest());
        return Outcome.ok("PENDING_APPROVAL");
      }
      default -> {
        transition(proposed, RequestStatus.EVALUATION_ERROR, decision.errorMessage(), event.getEventId(), null, null, null);
        throw new RetryableException(decision.errorMessage());
      }
    }
  }

  private Outcome terminal(ManifestRequest request, ManifestEvent event, RequestStatus target, String reason) {
    if (!RequestLifecycle.canTransition(request.status(), target)) {
      log.warn("{} for request {} in state {} ignored (out of order)", event.getEventType(), request.id(), request.status());
      return Outcome.ignored("OUT_OF_ORDER");
    }
    if (request.planId() != null && event.getPlanId() != null && !request.planId().equals(event.getPlanId())) {
      log.warn("{} for request {} refers to plan {} but current plan is {}; ignored", event.getEventType(), request.id(), event.getPlanId(), request.planId());
      return Outcome.ignored("PLAN_MISMATCH");
    }
    transition(request, target, reason, event.getEventId(), null, null, event.getStateVersion());
    approvals.cancelPending(request.id(), "Request ended: " + target);
    reservations.release(request.id(), target.name());
    if (target == RequestStatus.COMPLETED || target == RequestStatus.FAILED) {
      // The confirmed state (teams.repo.manifest) carries topic names only; remember the partitions this manifest declared.
      usage.recordDeclaredPartitions(request.teamId(), request.repositoryId(), request.environment(), UsageMath.declaredPartitions(request.manifest()), clock.instant());
    }
    audit.record("system", "REQUEST_" + target.name(), "manifest_request", request.id(), request.id(), Map.of("eventId", event.getEventId(), "reason", reason));
    return Outcome.ok(target.name());
  }

  private ManifestRequest transition(ManifestRequest request, RequestStatus to, String reason, String eventId, String planId, String planDigest, Long stateVersion) {
    ManifestRequest fresh = requests.findById(request.id()).orElseThrow();
    if (!RequestLifecycle.canTransition(fresh.status(), to)) {
      throw new IllegalStateException("Request " + request.id() + " is " + fresh.status() + "; cannot move to " + to);
    }
    if (!requests.transition(fresh.id(), fresh.version(), fresh.status(), to, reason, eventId, planId, planDigest, stateVersion, clock.instant())) {
      throw new RetryableException("Concurrent modification of request " + request.id());
    }
    log.info("Request {} {} -> {} ({})", request.id(), fresh.status(), to, reason);
    return requests.findById(request.id()).orElseThrow();
  }
}

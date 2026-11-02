package com.itland.governance.support;

import com.itland.governance.application.approvals.workflow.ApprovalWorkflowService;
import com.itland.governance.application.evaluation.PolicyEvaluationService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.decision.PolicyViolation;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.request.RequestStatus;
import com.itland.governance.infrastructure.persistence.DecisionRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import com.itland.governance.infrastructure.security.CallerContext;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the rows an approval needs (request, trigger decision, plan) and stubs OPA for the engine integration tests.
 * Postgres is the only container; Kafka consumers are disabled and the outbox relay is slowed down so commands written
 * by the delegates stay visible in {@code kafka_outbox}.
 */
public final class WorkflowFixtures {

  public static final String TEAM = "team-wf";
  public static final String REPOSITORY = "repo-wf";
  public static final String ENVIRONMENT = "production";
  public static final String REQUESTER = "dev-1";
  public static final CallerContext APPROVER_1 = new CallerContext("approver-1", Set.of(), Set.of(), Set.of("governance-approver"));
  public static final CallerContext APPROVER_2 = new CallerContext("approver-2", Set.of(), Set.of(), Set.of("governance-approver"));
  public static final CallerContext REQUESTER_WITH_APPROVER_ROLE = new CallerContext(REQUESTER, Set.of(TEAM), Set.of(), Set.of("governance-approver"));

  public static final String[] PROPERTIES = {
      "governance.kafka.consumer-enabled=false",
      "governance.kafka.usage-consumer-enabled=false",
      "governance.outbox.poll-interval=1h",
      "governance.opa.max-retries=0",
      "governance.opa.revision-check-interval=1h",
      "governance.workflow.approval-expiry-interval=1h",
      "flowable.process.async.executor.default-timer-job-acquire-wait-time=1s",
      "flowable.process.async.executor.default-async-job-acquire-wait-time=1s",
      "logging.structured.format.console=",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer"};

  public static PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("governance_wf").withUsername("governance").withPassword("governance");
  }

  private final ManifestRequestRepository requests;
  private final DecisionRepository decisions;
  private final ProposedPlanRepository plans;
  private final ApprovalWorkflowService workflow;
  private final PolicyEvaluationService evaluation;
  private final Blocking blocking;
  private final Clock clock;

  public WorkflowFixtures(ManifestRequestRepository requests, DecisionRepository decisions, ProposedPlanRepository plans,
                          ApprovalWorkflowService workflow, PolicyEvaluationService evaluation, Blocking blocking, Clock clock) {
    this.requests = requests;
    this.decisions = decisions;
    this.plans = plans;
    this.workflow = workflow;
    this.evaluation = evaluation;
    this.blocking = blocking;
    this.clock = clock;
  }

  public static Manifest manifest(String repository) {
    return new Manifest(TEAM, repository, ENVIRONMENT, new Manifest.Resources(
        List.of(new Manifest.TopicResource(TEAM + ".orders", 6, null, Map.of())), List.of(), List.of()));
  }

  public ManifestRequest newRequest(String repository) {
    Manifest manifest = manifest(repository);
    ManifestRequest r = new ManifestRequest("req-" + UUID.randomUUID(), REQUESTER, "key-" + UUID.randomUUID(), TEAM, repository, ENVIRONMENT,
        manifest, "sha256:" + UUID.randomUUID().toString().replace("-", ""), RequestStatus.RECEIVED, null, null, null, null, 0, clock.instant(), clock.instant(), null);
    requests.insert(r);
    return r;
  }

  public ManifestRequest transition(ManifestRequest r, RequestStatus to, String planId, String planDigest, Long stateVersion) {
    ManifestRequest fresh = requests.findById(r.id()).orElseThrow();
    if (!requests.transition(fresh.id(), fresh.version(), fresh.status(), to, "fixture", null, planId, planDigest, stateVersion, clock.instant())) {
      throw new IllegalStateException("fixture transition failed");
    }
    return requests.findById(r.id()).orElseThrow();
  }

  public GovernanceDecision decision(ManifestRequest r, GovernanceStage stage, DecisionType type, ProposedPlan plan, List<PolicyViolation> violations) {
    return new GovernanceDecision("dec-" + UUID.randomUUID(), r.id(), plan == null ? null : plan.planId(), stage, type, "rel-test", Map.of("core", "rel-test"),
        1, stage == GovernanceStage.REQUEST ? r.manifestDigest() : plan.planDigest(), List.of(), violations,
        type == DecisionType.REQUIRE_APPROVAL ? List.of(new ApprovalRequirement("PROD_TOPIC_DELETE", "approval required (test)", "topic", TEAM + ".orders", "enforce")) : List.of(),
        List.of(), type == DecisionType.ERROR ? "opa unavailable (test)" : null, 3L, clock.instant());
  }

  public ProposedPlan plan(ManifestRequest r, long stateVersion) {
    return new ProposedPlan("plan-" + UUID.randomUUID(), r.id(), "sha256:" + UUID.randomUUID().toString().replace("-", ""), r.manifestDigest(), stateVersion,
        List.of(new ProposedPlan.PlannedActionView("act-1", "topic", TEAM + ".orders", "TOPIC_CREATE", Map.of("partitions", "6"))),
        "evt-" + UUID.randomUUID(), clock.instant());
  }

  /** Stage 1: request PENDING_APPROVAL_STAGE1 with its REQUIRE_APPROVAL decision, then the workflow opened. */
  public ApprovalRequest openStage1(String repository) {
    return blocking.transactional(() -> {
      ManifestRequest r = newRequest(repository);
      GovernanceDecision d = decision(r, GovernanceStage.REQUEST, DecisionType.REQUIRE_APPROVAL, null, List.of());
      decisions.insert(d);
      r = transition(r, RequestStatus.PENDING_APPROVAL_STAGE1, null, null, null);
      return workflow.open(r, d, GovernanceStage.REQUEST, d.enforcedApprovals(), r.manifestDigest(), null, null);
    });
  }

  public record Stage2(ApprovalRequest approval, ManifestRequest request, ProposedPlan plan) {}

  /** Stage 2: request PENDING_APPROVAL_STAGE2 with the stored plan and its REQUIRE_APPROVAL decision, workflow opened. */
  public Stage2 openStage2(String repository, long stateVersion) {
    return blocking.transactional(() -> {
      ManifestRequest r = newRequest(repository);
      r = transition(r, RequestStatus.SUBMITTED, null, null, null);
      ProposedPlan plan = plan(r, stateVersion);
      plans.upsert(plan);
      r = transition(r, RequestStatus.PLAN_PROPOSED, plan.planId(), plan.planDigest(), stateVersion);
      GovernanceDecision d = decision(r, GovernanceStage.ACTION_PLAN, DecisionType.REQUIRE_APPROVAL, plan, List.of());
      decisions.insert(d);
      r = transition(r, RequestStatus.PENDING_APPROVAL_STAGE2, null, null, null);
      ApprovalRequest a = workflow.open(r, d, GovernanceStage.ACTION_PLAN, d.enforcedApprovals(), plan.planDigest(), plan.planId(), plan.planDigest());
      return new Stage2(a, r, plan);
    });
  }

  /** OPA answers every re-evaluation with the given type (violations only matter for DENY). Returns the decision ids it produced. */
  public AtomicReference<GovernanceDecision> stubOpa(DecisionType type, List<PolicyViolation> violations) {
    AtomicReference<GovernanceDecision> last = new AtomicReference<>();
    Mockito.reset(evaluation);
    Mockito.when(evaluation.evaluate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenAnswer(inv -> {
          GovernanceStage stage = inv.getArgument(0);
          ManifestRequest request = inv.getArgument(1);
          ProposedPlan plan = inv.getArgument(3);
          GovernanceDecision d = decision(request, stage, type, plan, violations);
          last.set(d);
          return Mono.just(d);
        });
    return last;
  }

  public static PolicyViolation violation(String policyId) {
    return new PolicyViolation(policyId, PolicyViolation.DENY, "denied by test", "topic", TEAM + ".orders", true, false, null, PolicyViolation.ENFORCE);
  }
}

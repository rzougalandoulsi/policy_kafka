package com.itland.governance.approvals;

import com.itland.governance.application.approvals.ApprovalService;
import com.itland.governance.application.approvals.workflow.ApprovalProcess;
import com.itland.governance.application.approvals.workflow.ApprovalWorkflowService;
import com.itland.governance.application.evaluation.PolicyEvaluationService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.approval.ApprovalEngine;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.approval.ApprovalVerdict;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.RequestStatus;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.AuditRepository;
import com.itland.governance.infrastructure.persistence.DecisionRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.OutboxRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import com.itland.governance.infrastructure.persistence.QuotaReservationRepository;
import com.itland.governance.support.TestJwt;
import com.itland.governance.support.TestSecurityConfig;
import com.itland.governance.support.WorkflowFixtures;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.job.api.Job;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.itland.governance.support.WorkflowFixtures.APPROVER_1;
import static com.itland.governance.support.WorkflowFixtures.APPROVER_2;
import static com.itland.governance.support.WorkflowFixtures.REQUESTER_WITH_APPROVER_ROLE;
import static com.itland.governance.support.WorkflowFixtures.TEAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The embedded Flowable engine running the approval processes against a real PostgreSQL (schema created by the
 * engine, correlation columns by Flyway V7), with OPA replaced by a controllable evaluation bean. Kafka is not
 * started: commands are asserted in the transactional outbox, which the relay publishes in the end-to-end suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "governance.workflow.engine=flowable",
    "governance.kafka.consumer-enabled=false",
    "governance.kafka.usage-consumer-enabled=false",
    "governance.outbox.poll-interval=1h",
    "governance.opa.max-retries=0",
    "governance.opa.revision-check-interval=1h",
    "governance.workflow.approval-expiry-interval=1h",
    "flowable.process.async.executor.default-timer-job-acquire-wait-time=1s",
    "flowable.process.async.executor.default-async-job-acquire-wait-time=1s",
    "logging.structured.format.console=",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer"})
@Import(TestSecurityConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalWorkflowIT {

  static final PostgreSQLContainer POSTGRES = WorkflowFixtures.postgres();

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @MockitoBean PolicyEvaluationService evaluation;

  @Autowired ApprovalWorkflowService workflow;
  @Autowired ApprovalRepository approvals;
  @Autowired ManifestRequestRepository requests;
  @Autowired DecisionRepository decisions;
  @Autowired ProposedPlanRepository plans;
  @Autowired OutboxRepository outbox;
  @Autowired AuditRepository audit;
  @Autowired QuotaReservationRepository reservations;
  @Autowired Blocking blocking;
  @Autowired Clock clock;
  @Autowired ProcessEngine engine;
  @Autowired RuntimeService runtime;
  @Autowired TaskService tasks;
  @Autowired RepositoryService repository;
  @Autowired ManagementService management;
  @Autowired HistoryService history;
  @Autowired SpringProcessEngineConfiguration configuration;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired ApplicationContext context;
  @Autowired JdbcClient jdbc;
  @LocalServerPort int port;

  WorkflowFixtures fx;
  WebTestClient client;

  @BeforeAll
  void setUp() {
    fx = new WorkflowFixtures(requests, decisions, plans, workflow, evaluation, blocking, clock);
    client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30)).build();
  }

  // ---- helpers ----

  ApprovalRequest approval(String id) {
    return blocking.transactional(() -> approvals.findById(id).orElseThrow());
  }

  ManifestRequest request(String id) {
    return blocking.transactional(() -> requests.findById(id).orElseThrow());
  }

  List<OutboxRepository.OutboxMessage> outboxOf(String requestId) {
    return blocking.transactional(() -> outbox.findByRequest(requestId));
  }

  List<String> auditActions(String requestId) {
    return blocking.transactional(() -> audit.query(null, null, requestId, null, null, 100)).stream().map(AuditRepository.AuditEntry::action).toList();
  }

  ApprovalService.Detail decide(com.itland.governance.infrastructure.security.CallerContext who, String approvalId, ApprovalVerdict verdict) {
    return workflow.decide(who, approvalId, verdict, "it").block(Duration.ofSeconds(30));
  }

  Task openTask(String approvalId) {
    return tasks.createTaskQuery().processInstanceBusinessKey(approvalId).taskDefinitionKey(ApprovalProcess.VERDICT_TASK).singleResult();
  }

  HistoricProcessInstance historic(String processInstanceId) {
    return history.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
  }

  static String commandType(OutboxRepository.OutboxMessage m) {
    return String.valueOf(m.headers().get("commandType"));
  }

  // ---- deployment ----

  @Test
  @Order(1)
  void threeDefinitionsAreDeployedOnceAtStartup() {
    for (String key : ApprovalProcess.DEFINITION_KEYS) {
      ProcessDefinition latest = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult();
      assertThat(latest).as(key).isNotNull();
      assertThat(latest.getVersion()).as(key).isEqualTo(1);
      assertThat(latest.getDeploymentId()).isNotNull();
    }
    assertThat(repository.createDeploymentQuery().deploymentName("governance-approvals").count()).isEqualTo(1);
    // Redeploying the unchanged resources under their deployed names creates no new version (duplicate filtering,
    // what every pod start does).
    String deploymentId = repository.createDeploymentQuery().deploymentName("governance-approvals").singleResult().getId();
    List<String> names = repository.getDeploymentResourceNames(deploymentId);
    assertThat(names).hasSize(3);
    var redeploy = repository.createDeployment().name("governance-approvals").enableDuplicateFiltering();
    for (String name : names) {
      redeploy.addInputStream(name, repository.getResourceAsStream(deploymentId, name));
    }
    redeploy.deploy();
    assertThat(repository.createDeploymentQuery().deploymentName("governance-approvals").count()).isEqualTo(1);
    assertThat(repository.createProcessDefinitionQuery().processDefinitionKey(ApprovalProcess.APPROVAL_DECISION).latestVersion().singleResult().getVersion()).isEqualTo(1);
  }

  @Test
  @Order(2)
  void readinessAndMetricsExposeTheEngine() {
    // OPA is not running in this suite, so the readiness group as a whole is DOWN; the engine contributor is UP.
    client.get().uri("/actuator/health/readiness").exchange().expectStatus().value(status -> assertThat(status).isIn(200, 503))
        .expectBody().jsonPath("$.components.workflowEngine.status").isEqualTo("UP")
        .jsonPath("$.components.workflowEngine.details.definitions.governance-manifest-approval").isEqualTo("v1")
        .jsonPath("$.components.workflowEngine.details.deadLetterJobs").isEqualTo(0);
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      String body = client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
      assertThat(body).contains("governance_workflow_jobs_deadletter").contains("governance_approvals_legacy_pending").contains("governance_workflow_instances_active");
    });
  }

  // ---- stage 1 ----

  @Test
  @Order(10)
  void stage1ApprovalRevalidatesAndSubmitsTheManifest() {
    ApprovalRequest opened = fx.openStage1("repo-s1-approve");
    assertThat(opened.engine()).isEqualTo(ApprovalEngine.FLOWABLE);
    assertThat(opened.processInstanceId()).isNotNull();
    assertThat(openTask(opened.id())).isNotNull();
    assertThat(openTask(opened.id()).getDueDate()).isEqualTo(Date.from(opened.expiresAt()));
    assertThat(management.createTimerJobQuery().processInstanceId(opened.processInstanceId()).count()).isEqualTo(1);

    fx.stubOpa(DecisionType.ALLOW, List.of());
    ApprovalService.Detail detail = decide(APPROVER_1, opened.id(), ApprovalVerdict.APPROVE);

    assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(detail.request().status()).isEqualTo(RequestStatus.SUBMITTED);
    assertThat(request(opened.requestId()).status()).isEqualTo(RequestStatus.SUBMITTED);
    List<OutboxRepository.OutboxMessage> commands = outboxOf(opened.requestId());
    assertThat(commands).hasSize(1);
    assertThat(commandType(commands.get(0))).isEqualTo("SUBMIT_MANIFEST");
    assertThat(commands.get(0).key()).isEqualTo(TEAM);
    assertThat(historic(opened.processInstanceId()).getEndTime()).isNotNull();
    assertThat(openTask(opened.id())).isNull();
    assertThat(management.createTimerJobQuery().processInstanceId(opened.processInstanceId()).count()).isZero();
    assertThat(auditActions(opened.requestId())).contains("APPROVAL_OPENED", "APPROVAL_DECISION", "APPROVAL_RELEASE_STAGE1");
    assertThat(blocking.transactional(() -> decisions.findByRequest(opened.requestId()))).hasSize(2); // trigger + revalidation

    // Idempotent replay of the same verdict after resolution.
    assertThat(decide(APPROVER_1, opened.id(), ApprovalVerdict.APPROVE).approval().status()).isEqualTo(ApprovalStatus.APPROVED);
  }

  @Test
  @Order(11)
  void stage1RejectionDeniesWithoutPublishing() {
    ApprovalRequest opened = fx.openStage1("repo-s1-reject");
    fx.stubOpa(DecisionType.ALLOW, List.of());
    ApprovalService.Detail detail = decide(APPROVER_1, opened.id(), ApprovalVerdict.REJECT);
    assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.REJECTED);
    assertThat(detail.approval().resolutionReason()).isEqualTo("Rejected by approver-1");
    assertThat(detail.request().status()).isEqualTo(RequestStatus.STAGE1_DENIED);
    assertThat(outboxOf(opened.requestId())).isEmpty();
    verify(evaluation, never()).evaluate(any(), any(), any(), any());
    assertThat(auditActions(opened.requestId())).contains("APPROVAL_REJECTED_RELEASE");
  }

  @Test
  @Order(12)
  void stage1DenyOnRevalidationEndsTheRequest() {
    ApprovalRequest opened = fx.openStage1("repo-s1-deny");
    fx.stubOpa(DecisionType.DENY, List.of(WorkflowFixtures.violation("NAMING_CONVENTION")));
    ApprovalService.Detail detail = decide(APPROVER_1, opened.id(), ApprovalVerdict.APPROVE);
    assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(detail.request().status()).isEqualTo(RequestStatus.STAGE1_DENIED);
    assertThat(detail.request().failureReason()).contains("NAMING_CONVENTION");
    assertThat(outboxOf(opened.requestId())).isEmpty();
  }

  // ---- stage 2 ----

  @Test
  @Order(20)
  void stage2ApprovalRevalidatesReservesAndAuthorizesThePlan() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-s2-approve", 7);
    var revalidation = fx.stubOpa(DecisionType.ALLOW, List.of());
    ApprovalService.Detail detail = decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE);

    assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(detail.request().status()).isEqualTo(RequestStatus.PLAN_AUTHORIZED);
    List<OutboxRepository.OutboxMessage> commands = outboxOf(s.request().id());
    assertThat(commands).hasSize(1);
    assertThat(commandType(commands.get(0))).isEqualTo("AUTHORIZE_PLAN");
    assertThat(commands.get(0).payloadJson()).contains(revalidation.get().id()).contains(s.plan().planId());
    assertThat(blocking.transactional(() -> reservations.findOpenByTeam(TEAM, clock.instant())))
        .anySatisfy(r -> assertThat(r.requestId()).isEqualTo(s.request().id()));
    assertThat(auditActions(s.request().id())).contains("APPROVAL_RELEASE_STAGE2");
    assertThat(historic(s.approval().processInstanceId()).getEndTime()).isNotNull();
  }

  @Test
  @Order(21)
  void stage2RejectionPublishesRejectPlan() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-s2-reject", 7);
    fx.stubOpa(DecisionType.ALLOW, List.of());
    ApprovalService.Detail detail = decide(APPROVER_1, s.approval().id(), ApprovalVerdict.REJECT);
    assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.REJECTED);
    assertThat(detail.request().status()).isEqualTo(RequestStatus.PLAN_REJECTED);
    List<OutboxRepository.OutboxMessage> commands = outboxOf(s.request().id());
    assertThat(commands).hasSize(1);
    assertThat(commandType(commands.get(0))).isEqualTo("REJECT_PLAN");
    assertThat(commands.get(0).payloadJson()).contains("APPROVAL_REJECTED");
    verify(evaluation, never()).evaluate(any(), any(), any(), any());
  }

  @Test
  @Order(22)
  void stage2StalePlanIsRejectedWithoutCallingOpa() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-s2-stale", 7);
    blocking.transactionalVoid(() -> plans.recordStateVersion(TEAM, "repo-s2-stale", WorkflowFixtures.ENVIRONMENT, 8, clock.instant()));
    fx.stubOpa(DecisionType.ALLOW, List.of());
    ApprovalService.Detail detail = decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE);
    assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(detail.request().status()).isEqualTo(RequestStatus.STALE_PLAN);
    List<OutboxRepository.OutboxMessage> commands = outboxOf(s.request().id());
    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).payloadJson()).contains("STALE_PLAN");
    verify(evaluation, never()).evaluate(any(), any(), any(), any());
    assertThat(auditActions(s.request().id())).contains("APPROVAL_RELEASE_STALE");
  }

  @Test
  @Order(23)
  void stage2DenyOnRevalidationPublishesPolicyDenied() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-s2-deny", 7);
    fx.stubOpa(DecisionType.DENY, List.of(WorkflowFixtures.violation("TEAM_QUOTA_EXCEEDED")));
    ApprovalService.Detail detail = decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE);
    assertThat(detail.request().status()).isEqualTo(RequestStatus.PLAN_REJECTED);
    List<OutboxRepository.OutboxMessage> commands = outboxOf(s.request().id());
    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).payloadJson()).contains("POLICY_DENIED").contains("TEAM_QUOTA_EXCEEDED");
  }

  @Test
  @Order(24)
  void opaErrorRollsTheVerdictBackAndTheTaskStaysOpen() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-s2-error", 7);
    fx.stubOpa(DecisionType.ERROR, List.of());
    assertThatThrownBy(() -> decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE))
        .isInstanceOf(ApprovalService.ApprovalRuleException.class).hasMessageContaining("re-evaluation unavailable");

    ApprovalRequest after = approval(s.approval().id());
    assertThat(after.status()).isEqualTo(ApprovalStatus.PENDING);
    assertThat(blocking.transactional(() -> approvals.decisions(after.id()))).isEmpty();
    assertThat(request(s.request().id()).status()).isEqualTo(RequestStatus.PENDING_APPROVAL_STAGE2);
    assertThat(outboxOf(s.request().id())).isEmpty();
    assertThat(openTask(after.id())).isNotNull();
    assertThat(blocking.transactional(() -> decisions.findByRequest(s.request().id()))).hasSize(1); // the ERROR decision rolled back too

    // OPA is back: the same approver retries and the plan is released.
    fx.stubOpa(DecisionType.ALLOW, List.of());
    assertThat(decide(APPROVER_1, after.id(), ApprovalVerdict.APPROVE).request().status()).isEqualTo(RequestStatus.PLAN_AUTHORIZED);
  }

  @Test
  @Order(25)
  void planReplacedUnderneathTheApprovalFailsClosed() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-s2-replaced", 7);
    // Stored plans are immutable (upsert ignores conflicts); corrupt the stored digest directly to simulate a swap.
    blocking.transactionalVoid(() -> jdbc.sql("UPDATE proposed_plan SET plan_digest = 'sha256:replaced' WHERE plan_id = :p").param("p", s.plan().planId()).update());
    fx.stubOpa(DecisionType.ALLOW, List.of());
    assertThatThrownBy(() -> decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("current plan");
    assertThat(approval(s.approval().id()).status()).isEqualTo(ApprovalStatus.PENDING);
    assertThat(openTask(s.approval().id())).isNotNull();
    assertThat(outboxOf(s.request().id())).isEmpty();
  }

  // ---- quorum, separation of duties, idempotency, concurrency ----

  @Test
  @Order(30)
  void quorumOfTwoNeedsTwoDistinctApprovers() {
    withQuorum(2, () -> {
      WorkflowFixtures.Stage2 s = fx.openStage2("repo-quorum", 7);
      fx.stubOpa(DecisionType.ALLOW, List.of());
      ApprovalService.Detail first = decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE);
      assertThat(first.approval().status()).isEqualTo(ApprovalStatus.PENDING);
      assertThat(first.request().status()).isEqualTo(RequestStatus.PENDING_APPROVAL_STAGE2);
      Task again = openTask(s.approval().id());
      assertThat(again).as("a new verdict task iteration").isNotNull();
      assertThat(runtime.getVariable(again.getProcessInstanceId(), ApprovalProcess.APPROVED_COUNT)).isEqualTo(1);
      // The same approver again counts once.
      assertThat(decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE).approval().status()).isEqualTo(ApprovalStatus.PENDING);
      assertThat(blocking.transactional(() -> approvals.decisions(s.approval().id()))).hasSize(1);
      ApprovalService.Detail second = decide(APPROVER_2, s.approval().id(), ApprovalVerdict.APPROVE);
      assertThat(second.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
      assertThat(second.request().status()).isEqualTo(RequestStatus.PLAN_AUTHORIZED);
      assertThat(outboxOf(s.request().id())).hasSize(1);

      WorkflowFixtures.Stage2 r = fx.openStage2("repo-quorum-reject", 7);
      decide(APPROVER_1, r.approval().id(), ApprovalVerdict.APPROVE);
      ApprovalService.Detail rejected = decide(APPROVER_2, r.approval().id(), ApprovalVerdict.REJECT);
      assertThat(rejected.approval().status()).isEqualTo(ApprovalStatus.REJECTED);
      assertThat(rejected.request().status()).isEqualTo(RequestStatus.PLAN_REJECTED);
    });
  }

  @Test
  @Order(31)
  void separationOfDutiesIsEnforcedBeforeTheTaskIsTouched() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-sod", 7);
    assertThatThrownBy(() -> decide(REQUESTER_WITH_APPROVER_ROLE, s.approval().id(), ApprovalVerdict.APPROVE))
        .isInstanceOf(ApprovalService.ApprovalRuleException.class).hasMessageContaining("Separation of duties");
    assertThat(blocking.transactional(() -> approvals.decisions(s.approval().id()))).isEmpty();
    assertThat(openTask(s.approval().id())).isNotNull();
    assertThat(approval(s.approval().id()).status()).isEqualTo(ApprovalStatus.PENDING);
  }

  @Test
  @Order(32)
  void openingTwiceForTheSameRequestAndStageReusesTheOpenApproval() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-duplicate", 7);
    GovernanceDecision trigger = blocking.transactional(() -> decisions.findByRequest(s.request().id())).get(0);
    ApprovalRequest again = blocking.transactional(() -> workflow.open(request(s.request().id()), trigger, s.approval().stage(), List.of(),
        s.plan().planDigest(), s.plan().planId(), s.plan().planDigest()));
    assertThat(again.id()).isEqualTo(s.approval().id());
    // The called sub-process inherits the business key, hence one parent and one child instance.
    assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(s.approval().id()).processDefinitionKey(ApprovalProcess.ACTION_PLAN_APPROVAL).count()).isEqualTo(1);
    assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(s.approval().id()).processDefinitionKey(ApprovalProcess.APPROVAL_DECISION).count()).isEqualTo(1);
    assertThat(tasks.createTaskQuery().processInstanceBusinessKey(s.approval().id()).count()).isEqualTo(1);
  }

  @Test
  @Order(33)
  void concurrentDecisionsOnOneApprovalAreSerialised() throws Exception {
    withQuorum(2, () -> {
      WorkflowFixtures.Stage2 s = fx.openStage2("repo-race", 7);
      fx.stubOpa(DecisionType.ALLOW, List.of());
      CountDownLatch go = new CountDownLatch(1);
      CompletableFuture<ApprovalStatus> a = CompletableFuture.supplyAsync(() -> { awaitLatch(go); return decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE).approval().status(); });
      CompletableFuture<ApprovalStatus> b = CompletableFuture.supplyAsync(() -> { awaitLatch(go); return decide(APPROVER_2, s.approval().id(), ApprovalVerdict.APPROVE).approval().status(); });
      go.countDown();
      try {
        List<ApprovalStatus> outcomes = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        assertThat(outcomes).containsExactlyInAnyOrder(ApprovalStatus.PENDING, ApprovalStatus.APPROVED);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      assertThat(approval(s.approval().id()).status()).isEqualTo(ApprovalStatus.APPROVED);
      assertThat(request(s.request().id()).status()).isEqualTo(RequestStatus.PLAN_AUTHORIZED);
      assertThat(outboxOf(s.request().id())).hasSize(1);
      assertThat(blocking.transactional(() -> approvals.decisions(s.approval().id()))).hasSize(2);
    });
  }

  // ---- deadline, cancellation ----

  @Test
  @Order(40)
  void deadlineTimerExpiresTheApprovalThroughTheAsyncExecutor() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-expire", 7);
    Job timer = management.createTimerJobQuery().processInstanceId(s.approval().processInstanceId()).singleResult();
    assertThat(timer.getDuedate()).isEqualTo(Date.from(s.approval().expiresAt()));
    try {
      configuration.getClock().setCurrentTime(Date.from(s.approval().expiresAt().plusSeconds(5)));
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(approval(s.approval().id()).status()).isEqualTo(ApprovalStatus.EXPIRED));
    } finally {
      configuration.getClock().reset();
    }
    assertThat(request(s.request().id()).status()).isEqualTo(RequestStatus.APPROVAL_EXPIRED);
    List<OutboxRepository.OutboxMessage> commands = outboxOf(s.request().id());
    assertThat(commands).hasSize(1);
    assertThat(commandType(commands.get(0))).isEqualTo("REJECT_PLAN");
    assertThat(commands.get(0).payloadJson()).contains("APPROVAL_EXPIRED");
    assertThat(auditActions(s.request().id())).contains("APPROVAL_EXPIRED");
    assertThat(historic(s.approval().processInstanceId()).getEndTime()).isNotNull();
    assertThat(management.createDeadLetterJobQuery().count()).isZero();
    // A late verdict is refused on the resolved approval.
    assertThatThrownBy(() -> decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE))
        .isInstanceOf(ApprovalService.ApprovalRuleException.class).hasMessageContaining("EXPIRED");
  }

  @Test
  @Order(41)
  void stage1DeadlineExpiresWithoutPublishing() {
    ApprovalRequest opened = fx.openStage1("repo-expire-s1");
    withoutAsyncExecutor(() -> {
      Job timer = management.createTimerJobQuery().processInstanceId(opened.processInstanceId()).singleResult();
      management.moveTimerToExecutableJob(timer.getId());
      management.executeJob(timer.getId());
    });
    assertThat(approval(opened.id()).status()).isEqualTo(ApprovalStatus.EXPIRED);
    assertThat(request(opened.requestId()).status()).isEqualTo(RequestStatus.APPROVAL_EXPIRED);
    assertThat(outboxOf(opened.requestId())).isEmpty();
  }

  @Test
  @Order(42)
  void cancellationEndsTheProcessWithoutPublishing() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-cancel", 7);
    blocking.transactionalVoid(() -> workflow.cancel(s.request().id(), "Request ended: COMPLETED"));
    ApprovalRequest cancelled = approval(s.approval().id());
    assertThat(cancelled.status()).isEqualTo(ApprovalStatus.CANCELLED);
    assertThat(cancelled.resolutionReason()).isEqualTo("Request ended: COMPLETED");
    assertThat(historic(s.approval().processInstanceId()).getEndTime()).isNotNull();
    assertThat(outboxOf(s.request().id())).isEmpty();
    assertThat(auditActions(s.request().id())).contains("APPROVAL_CANCELLED");
    assertThat(management.createTimerJobQuery().processInstanceId(s.approval().processInstanceId()).count()).isZero();
    // Cancelling again is a no-op.
    blocking.transactionalVoid(() -> workflow.cancel(s.request().id(), "again"));
    assertThat(approval(s.approval().id()).resolutionReason()).isEqualTo("Request ended: COMPLETED");
  }

  // ---- restart recovery, multiple engines on one database ----

  @Test
  @Order(50)
  void aSecondEngineOnTheSameDatabaseRunsTheDueTimerExactlyOnce() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-restart", 7);
    SpringProcessEngineConfiguration second = new SpringProcessEngineConfiguration();
    second.setEngineName("governance-replica");
    second.setDataSource(dataSource);
    second.setTransactionManager(transactionManager);
    second.setApplicationContext(context);
    second.setDatabaseSchemaUpdate("true");
    second.setAsyncExecutorActivate(false);
    second.setDisableIdmEngine(true);
    second.setDisableEventRegistry(true);
    ProcessEngine replica = second.buildProcessEngine();
    try {
      // The first engine's executor is paused so the replica (a freshly started pod) is the one that runs the job.
      withoutAsyncExecutor(() -> {
        Job timer = replica.getManagementService().createTimerJobQuery().processInstanceId(s.approval().processInstanceId()).singleResult();
        assertThat(timer).isNotNull();
        replica.getManagementService().moveTimerToExecutableJob(timer.getId());
        replica.getManagementService().executeJob(timer.getId());
      });
    } finally {
      replica.close();
    }
    assertThat(approval(s.approval().id()).status()).isEqualTo(ApprovalStatus.EXPIRED);
    assertThat(auditActions(s.request().id()).stream().filter("APPROVAL_EXPIRED"::equals).count()).isEqualTo(1);
    assertThat(outboxOf(s.request().id())).hasSize(1);
    // The first engine sees the ended instance and has nothing left to run.
    assertThat(historic(s.approval().processInstanceId()).getEndTime()).isNotNull();
    assertThat(management.createTimerJobQuery().processInstanceId(s.approval().processInstanceId()).count()).isZero();
  }

  // ---- definition versioning ----

  @Test
  @Order(60)
  void inFlightInstancesStayOnTheirDefinitionVersionAfterAnUpgrade() throws Exception {
    WorkflowFixtures.Stage2 before = fx.openStage2("repo-upgrade-before", 7);
    String v1 = new String(getClass().getResourceAsStream("/processes/approval-decision.bpmn20.xml").readAllBytes(), StandardCharsets.UTF_8);
    String v2 = v1.replace("v1: user task loop with quorum gateway.", "v2: same contract, documentation changed.");
    int previous = repository.createProcessDefinitionQuery().processDefinitionKey(ApprovalProcess.APPROVAL_DECISION).latestVersion().singleResult().getVersion();
    repository.createDeployment().name("governance-approvals").addString("approval-decision.bpmn20.xml", v2).deploy();
    assertThat(repository.createProcessDefinitionQuery().processDefinitionKey(ApprovalProcess.APPROVAL_DECISION).latestVersion().singleResult().getVersion()).isEqualTo(previous + 1);

    WorkflowFixtures.Stage2 after = fx.openStage2("repo-upgrade-after", 7);
    HistoricProcessInstance childBefore = history.createHistoricProcessInstanceQuery().superProcessInstanceId(before.approval().processInstanceId()).singleResult();
    HistoricProcessInstance childAfter = history.createHistoricProcessInstanceQuery().superProcessInstanceId(after.approval().processInstanceId()).singleResult();
    assertThat(childBefore.getProcessDefinitionVersion()).isEqualTo(previous);
    assertThat(childAfter.getProcessDefinitionVersion()).isEqualTo(previous + 1);

    fx.stubOpa(DecisionType.ALLOW, List.of());
    assertThat(decide(APPROVER_1, before.approval().id(), ApprovalVerdict.APPROVE).request().status()).isEqualTo(RequestStatus.PLAN_AUTHORIZED);
    assertThat(decide(APPROVER_1, after.approval().id(), ApprovalVerdict.APPROVE).request().status()).isEqualTo(RequestStatus.PLAN_AUTHORIZED);
    assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(childBefore.getId()).singleResult().getProcessDefinitionVersion()).isEqualTo(previous);
  }

  // ---- public API shape ----

  @Test
  @Order(70)
  void approvalApiExposesTheCorrelationAdditively() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-api", 7);
    String approver = TestJwt.token("approver-9", List.of(), List.of(), List.of("governance-approver"));
    client.get().uri("/api/v1/approvals/" + s.approval().id()).header("Authorization", "Bearer " + approver).exchange().expectStatus().isOk()
        .expectBody()
        .jsonPath("$.approval.id").isEqualTo(s.approval().id())
        .jsonPath("$.approval.status").isEqualTo("PENDING")
        .jsonPath("$.approval.engine").isEqualTo("FLOWABLE")
        .jsonPath("$.approval.processInstanceId").isEqualTo(s.approval().processInstanceId())
        .jsonPath("$.requestStatus").isEqualTo("PENDING_APPROVAL_STAGE2")
        .jsonPath("$.teamId").isEqualTo(TEAM);
    fx.stubOpa(DecisionType.ALLOW, List.of());
    client.post().uri("/api/v1/approvals/" + s.approval().id() + "/decisions").header("Authorization", "Bearer " + approver)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(Map.of("verdict", "APPROVE", "comment", "via api")).exchange()
        .expectStatus().isOk().expectBody()
        .jsonPath("$.approval.status").isEqualTo("APPROVED")
        .jsonPath("$.requestStatus").isEqualTo("PLAN_AUTHORIZED")
        .jsonPath("$.decisions[0].approverId").isEqualTo("approver-9");
  }

  // ---- support ----

  void withQuorum(int required, Runnable body) {
    String id = "apr-quorum-" + java.util.UUID.randomUUID();
    blocking.transactionalVoid(() -> {
      approvals.insertConfiguration(id, Map.of("approverRoles", List.of("governance-approver"), "requiredApprovals", required,
          "separationOfDuties", true, "ttlHours", 72), "it", clock.instant());
      approvals.activateConfiguration(id, clock.instant());
    });
    try {
      body.run();
    } finally {
      blocking.transactionalVoid(() -> approvals.activateConfiguration("apr-initial", clock.instant()));
    }
  }

  /** Deterministic job execution: the engine's own executor would otherwise race the explicit executeJob call. */
  void withoutAsyncExecutor(Runnable body) {
    configuration.getAsyncExecutor().shutdown();
    try {
      body.run();
    } finally {
      configuration.getAsyncExecutor().start();
    }
  }

  static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

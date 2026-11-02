package com.itland.governance.approvals;

import com.itland.governance.application.approvals.ApprovalExpiryJob;
import com.itland.governance.application.approvals.ApprovalService;
import com.itland.governance.application.approvals.workflow.ApprovalWorkflowService;
import com.itland.governance.application.evaluation.PolicyEvaluationService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.domain.approval.ApprovalEngine;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.approval.ApprovalVerdict;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.request.RequestStatus;
import com.itland.governance.infrastructure.observability.MetricsRefreshJob;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.AuditRepository;
import com.itland.governance.infrastructure.persistence.DecisionRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.OutboxRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import com.itland.governance.support.TestSecurityConfig;
import com.itland.governance.support.WorkflowFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.Job;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static com.itland.governance.support.WorkflowFixtures.APPROVER_1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shadow mode: the legacy implementation stays authoritative (engine = LEGACY rows, quorum in Java, TTL sweep) and
 * every approval is mirrored into a process instance that records what it would have done. Proves the comparison
 * machinery, that only one implementation publishes, and that legacy rows remain decidable and expirable (the drain
 * path of the migration).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "governance.workflow.engine=shadow",
    "governance.kafka.consumer-enabled=false",
    "governance.kafka.usage-consumer-enabled=false",
    "governance.outbox.poll-interval=1h",
    "governance.opa.max-retries=0",
    "governance.opa.revision-check-interval=1h",
    "governance.workflow.approval-expiry-interval=1h",
    "governance.metrics.refresh-interval=1h",
    "flowable.process.async.executor.default-timer-job-acquire-wait-time=1s",
    "logging.structured.format.console=",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer"})
@Import(TestSecurityConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalWorkflowShadowIT {

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
  @Autowired ApprovalExpiryJob expiryJob;
  @Autowired MetricsRefreshJob metricsJob;
  @Autowired MeterRegistry registry;
  @Autowired ApprovalRepository approvals;
  @Autowired ManifestRequestRepository requests;
  @Autowired DecisionRepository decisions;
  @Autowired ProposedPlanRepository plans;
  @Autowired OutboxRepository outbox;
  @Autowired AuditRepository audit;
  @Autowired JdbcClient jdbc;
  @Autowired Blocking blocking;
  @Autowired Clock clock;
  @Autowired ManagementService management;
  @Autowired HistoryService history;
  @Autowired SpringProcessEngineConfiguration configuration;

  WorkflowFixtures fx;

  @BeforeAll
  void setUp() {
    fx = new WorkflowFixtures(requests, decisions, plans, workflow, evaluation, blocking, clock);
  }

  ApprovalRequest approval(String id) {
    return blocking.transactional(() -> approvals.findById(id).orElseThrow());
  }

  List<AuditRepository.AuditEntry> shadowRecords(String requestId) {
    return blocking.transactional(() -> audit.query(null, null, requestId, null, null, 100)).stream()
        .filter(e -> "WORKFLOW_SHADOW_OUTCOME".equals(e.action())).toList();
  }

  @Test
  @Order(1)
  void modeIsShadowAndApprovalsStayLegacyWithAMirrorProcess() {
    assertThat(workflow.mode()).isEqualTo(GovernanceProperties.Workflow.Engine.SHADOW);
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-shadow-approve", 7);
    assertThat(s.approval().engine()).isEqualTo(ApprovalEngine.LEGACY);
    assertThat(s.approval().processInstanceId()).isNotNull();
    metricsJob.refresh();
    assertThat(registry.get("governance.approvals.legacy_pending").gauge().value()).isGreaterThanOrEqualTo(1);

    fx.stubOpa(DecisionType.ALLOW, List.of());
    ApprovalService.Detail detail = workflow.decide(APPROVER_1, s.approval().id(), ApprovalVerdict.APPROVE, "shadow").block(Duration.ofSeconds(30));
    assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(detail.request().status()).isEqualTo(RequestStatus.PLAN_AUTHORIZED);

    // Exactly one implementation published.
    List<OutboxRepository.OutboxMessage> commands = blocking.transactional(() -> outbox.findByRequest(s.request().id()));
    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).headers().get("commandType")).isEqualTo("AUTHORIZE_PLAN");
    // The mirror reached the same outcome and recorded the comparison; the process ended without releasing anything.
    List<AuditRepository.AuditEntry> shadow = shadowRecords(s.request().id());
    assertThat(shadow).hasSize(1);
    assertThat(shadow.get(0).details()).containsEntry("engineOutcome", "APPROVED").containsEntry("legacyStatus", "APPROVED").containsEntry("match", true);
    assertThat(history.createHistoricProcessInstanceQuery().processInstanceId(s.approval().processInstanceId()).singleResult().getEndTime()).isNotNull();
    assertThat(registry.get("governance.workflow.shadow.outcomes").tag("match", "true").counter().count()).isGreaterThanOrEqualTo(1);
    assertThat(blocking.transactional(() -> decisions.findByRequest(s.request().id()))).hasSize(2); // trigger + one revalidation, not two
  }

  @Test
  @Order(2)
  void rejectionAndCancellationAreMirrored() {
    ApprovalRequest rejected = fx.openStage1("repo-shadow-reject");
    ApprovalService.Detail detail = workflow.decide(APPROVER_1, rejected.id(), ApprovalVerdict.REJECT, "no").block(Duration.ofSeconds(30));
    assertThat(detail.request().status()).isEqualTo(RequestStatus.STAGE1_DENIED);
    assertThat(shadowRecords(rejected.requestId()).get(0).details()).containsEntry("engineOutcome", "REJECTED").containsEntry("match", true);

    WorkflowFixtures.Stage2 cancelled = fx.openStage2("repo-shadow-cancel", 7);
    blocking.transactionalVoid(() -> workflow.cancel(cancelled.request().id(), "Request ended: FAILED"));
    assertThat(approval(cancelled.approval().id()).status()).isEqualTo(ApprovalStatus.CANCELLED);
    assertThat(shadowRecords(cancelled.request().id()).get(0).details()).containsEntry("engineOutcome", "CANCELLED").containsEntry("match", true);
    assertThat(blocking.transactional(() -> outbox.findByRequest(cancelled.request().id()))).isEmpty();
  }

  @Test
  @Order(3)
  void legacyRowsStillExpireThroughTheSweepWhileTheMirrorOnlyRecords() {
    WorkflowFixtures.Stage2 s = fx.openStage2("repo-shadow-expire", 7);
    // The engine's deadline fires first (the sweep runs on its own schedule): recorded as a mismatch against the still
    // pending legacy row, released by nobody.
    configuration.getAsyncExecutor().shutdown();
    try {
      Job timer = management.createTimerJobQuery().processInstanceId(s.approval().processInstanceId()).singleResult();
      management.moveTimerToExecutableJob(timer.getId());
      management.executeJob(timer.getId());
    } finally {
      configuration.getAsyncExecutor().start();
    }
    assertThat(approval(s.approval().id()).status()).isEqualTo(ApprovalStatus.PENDING);
    assertThat(shadowRecords(s.request().id()).get(0).details()).containsEntry("engineOutcome", "EXPIRED").containsEntry("legacyStatus", "PENDING").containsEntry("match", false);
    assertThat(blocking.transactional(() -> outbox.findByRequest(s.request().id()))).isEmpty();

    // The legacy sweep (engine = LEGACY rows only) resolves it and publishes REJECT_PLAN exactly once.
    blocking.transactionalVoid(() -> jdbc.sql("UPDATE approval_request SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", s.approval().id()).update());
    expiryJob.expire();
    assertThat(approval(s.approval().id()).status()).isEqualTo(ApprovalStatus.EXPIRED);
    assertThat(blocking.transactional(() -> requests.findById(s.request().id())).orElseThrow().status()).isEqualTo(RequestStatus.APPROVAL_EXPIRED);
    List<OutboxRepository.OutboxMessage> commands = blocking.transactional(() -> outbox.findByRequest(s.request().id()));
    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).payloadJson()).contains("APPROVAL_EXPIRED");
  }

  @Test
  @Order(4)
  void metricsCountResolutionsByEngine() {
    assertThat(registry.get("governance.approvals.resolved").tag("engine", "LEGACY").counters()).isNotEmpty();
    assertThat(registry.find("governance.approvals.resolved").tag("engine", "FLOWABLE").counters()).isEmpty();
  }
}

package com.itland.governance.e2e;

import com.itland.governance.support.Bundles;
import com.itland.governance.support.PlatformContainers;
import com.itland.governance.support.TestJwt;
import com.itland.governance.support.TestSecurityConfig;
import com.itland.platform.avro.ManifestEvent;
import com.itland.platform.avro.ManifestEventType;
import com.itland.platform.avro.PlanProposedPayload;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.RecordNameStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.itland.governance.application.approvals.workflow.ApprovalWorkflowService;
import com.itland.governance.configuration.GovernanceProperties;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.Job;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full platform flow: REST -> Stage 1 (OPA) -> requests.manifest -> Orchestrator (plan) -> response.manifest ->
 * Stage 2 (OPA) -> AUTHORIZE_PLAN -> Orchestrator release -> Processor stand-in -> completion -> REST.
 * Scenarios run in order because they build on confirmed state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "server.port=" + PlatformContainers.GOVERNANCE_PORT,
    "governance.opa.bundle-token=" + PlatformContainers.BUNDLE_TOKEN,
    "governance.opa.response-timeout=3s",
    "governance.opa.max-retries=1",
    "governance.opa.revision-check-interval=2s",
    "governance.outbox.poll-interval=200ms",
    "governance.inbox.retry-poll-interval=2s",
    "governance.workflow.evaluation-retry-backoff=2s",
    "governance.workflow.approval-expiry-interval=5s",
    "governance.metrics.refresh-interval=5s",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer"})
@Import(TestSecurityConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernanceEndToEndIT {

  static {
    PlatformContainers.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PlatformContainers.POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", PlatformContainers.POSTGRES::getUsername);
    r.add("spring.datasource.password", PlatformContainers.POSTGRES::getPassword);
    r.add("governance.kafka.bootstrap-servers", PlatformContainers.KAFKA::getBootstrapServers);
    r.add("governance.kafka.schema-registry-url", PlatformContainers::schemaRegistryUrl);
    r.add("governance.opa.url", PlatformContainers::opaUrl);
  }

  static final String DEV = TestJwt.token("dev-1", List.of("team-a"), List.of("development", "production"), List.of());
  static final String DEV_WITH_APPROVER_ROLE = TestJwt.token("dev-1", List.of("team-a"), List.of("development", "production"), List.of("governance-approver"));
  static final String OUTSIDER = TestJwt.token("dev-9", List.of("team-b"), List.of("development"), List.of());
  static final String APPROVER = TestJwt.token("approver-1", List.of(), List.of(), List.of("governance-approver"));
  static final String ADMIN = TestJwt.token("admin-1", List.of(), List.of(), List.of("governance-admin"));
  static final String AUDITOR = TestJwt.token("auditor-1", List.of(), List.of(), List.of("governance-auditor"));

  @Autowired JdbcClient jdbc;
  @Autowired ApprovalWorkflowService workflow;
  @Autowired ManagementService management;
  @Autowired SpringProcessEngineConfiguration engineConfiguration;
  WebTestClient client;
  Map<String, String> bundleV1Ids = new java.util.TreeMap<>();

  @BeforeAll
  void setUp() {
    client = WebTestClient.bindToServer().baseUrl("http://localhost:" + PlatformContainers.GOVERNANCE_PORT)
        .responseTimeout(Duration.ofSeconds(60)).build();
  }

  // ---------------------------------------------------------------- helpers

  static Map<String, Object> manifest(String env, List<Map<String, Object>> topics, List<Map<String, Object>> rbac) {
    return manifest("repo-1", env, topics, rbac);
  }

  static Map<String, Object> manifest(String repository, String env, List<Map<String, Object>> topics, List<Map<String, Object>> rbac) {
    return Map.of("teamId", "team-a", "repositoryId", repository, "environment", env,
        "resources", Map.of("topics", topics, "rbac", rbac, "identityPools", List.of()));
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> configureTeam(List<String> prefixes, Map<String, Object> quotas, String comment) {
    return client.put().uri("/api/v1/admin/teams/team-a").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("authorizedPrefixes", prefixes, "quotas", quotas, "allowedRoles", List.of(), "environments", Map.of(), "comment", comment))
        .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> teamUsage() {
    return client.get().uri("/api/v1/admin/teams/team-a/usage").header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> activeConfigurationContent() {
    List<Map<String, Object>> versions = client.get().uri("/api/v1/admin/policy-configurations").header("Authorization", "Bearer " + ADMIN).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    return new java.util.HashMap<>((Map<String, Object>) versions.stream().filter(v -> "ACTIVE".equals(v.get("status"))).findFirst().orElseThrow().get("content"));
  }

  @SuppressWarnings("unchecked")
  void activateConfiguration(Map<String, Object> content, String comment) {
    Map<String, Object> draft = client.post().uri("/api/v1/admin/policy-configurations").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("content", content, "comment", comment)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    client.post().uri("/api/v1/admin/policy-configurations/" + draft.get("id") + "/activate").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk();
  }

  static Map<String, Object> topic(String name, int partitions) {
    return Map.of("name", name, "partitions", partitions, "retentionMs", 86400000L, "config", Map.of());
  }

  WebTestClient.ResponseSpec submit(String token, Map<String, Object> manifest, String key) {
    return client.post().uri("/api/v1/manifests").header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).bodyValue(manifest).exchange();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> submitAccepted(String token, Map<String, Object> manifest) {
    return submit(token, manifest, UUID.randomUUID().toString()).expectStatus().isAccepted().expectBody(Map.class).returnResult().getResponseBody();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> request(String token, String requestId) {
    return client.get().uri("/api/v1/requests/" + requestId).header("Authorization", "Bearer " + token).exchange()
        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  Map<String, Object> awaitStatus(String requestId, String... expected) {
    List<String> wanted = List.of(expected);
    await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(request(DEV, requestId).get("status")).isIn(wanted));
    return request(DEV, requestId);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> decisions(String requestId) {
    return client.get().uri("/api/v1/requests/" + requestId + "/decisions").header("Authorization", "Bearer " + DEV).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> diagnostics() {
    return client.get().uri("/api/v1/admin/policy-bundles/diagnostics").header("Authorization", "Bearer " + ADMIN).exchange()
        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  /** Waits until OPA runs exactly the expected revision for each given bundle and the whole release matches. */
  @SuppressWarnings("unchecked")
  void awaitRelease(Map<String, String> expected) {
    try {
      await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
        Map<String, Object> d = diagnostics();
        assertThat(d).containsEntry("match", true).containsEntry("ready", true);
        Map<String, Map<String, Object>> bundles = (Map<String, Map<String, Object>>) d.get("bundles");
        expected.forEach((name, rev) -> assertThat(bundles.get(name)).containsEntry("loaded", rev).containsEntry("match", true));
      });
    } catch (RuntimeException e) {
      System.err.println("==== OPA container logs ====\n" + PlatformContainers.opaLogs());
      throw e;
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, String> releaseRevisions() throws Exception {
    Map<String, Object> release = new tools.jackson.databind.json.JsonMapper().readValue(Files.readString(PlatformContainers.RELEASE), Map.class);
    Map<String, String> out = new java.util.TreeMap<>();
    ((Map<String, Map<String, Object>>) release.get("bundles")).forEach((n, b) -> out.put(n, (String) b.get("revision")));
    return out;
  }

  /** Uploads every built bundle of dist/release.json; returns bundle name -> version id. */
  Map<String, String> uploadRelease(String comment) throws Exception {
    Map<String, String> ids = new java.util.TreeMap<>();
    for (String name : PlatformContainers.BUNDLES) ids.put(name, uploadBundle(Files.readAllBytes(PlatformContainers.bundle(name)), comment + " " + name));
    return ids;
  }

  void activateRelease(Map<String, String> ids) {
    client.post().uri("/api/v1/admin/policy-releases").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("versionIds", List.copyOf(ids.values()))).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.id").value(id -> assertThat((String) id).startsWith("rel-")).jsonPath("$.revisions.core").isNotEmpty();
  }

  @SuppressWarnings("unchecked")
  String uploadBundle(byte[] bytes, String comment) throws Exception {
    Map<String, Object> body = Map.of("bundleBase64", Base64.getEncoder().encodeToString(bytes),
        "testReportJson", Files.readString(PlatformContainers.TEST_REPORT), "comment", comment);
    Map<String, Object> created = client.post().uri("/api/v1/admin/policy-bundles").header("Authorization", "Bearer " + ADMIN)
        .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    return (String) created.get("id");
  }

  // ---------------------------------------------------------------- scenarios

  @Test
  @Order(1)
  void withoutAnActiveBundleGovernanceFailsClosed() {
    submit(DEV, manifest("development", List.of(topic("team-a.orders", 6)), List.of()), "no-bundle-" + UUID.randomUUID())
        .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        .expectBody().jsonPath("$.code").isEqualTo("POLICY_EVALUATION_UNAVAILABLE");
  }

  @Test
  @Order(2)
  void adminUploadsAndActivatesTheReleaseAndOpaAdoptsAllSixBundles() throws Exception {
    client.post().uri("/api/v1/admin/policy-bundles").header("Authorization", "Bearer " + DEV).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("bundleBase64", "AA==", "testReportJson", "[]")).exchange().expectStatus().isForbidden();

    bundleV1Ids.putAll(uploadRelease("v1"));
    // A partial release is refused: every bundle must be present.
    client.post().uri("/api/v1/admin/policy-releases").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("versionIds", List.of(bundleV1Ids.get("core")))).exchange().expectStatus().isBadRequest();
    activateRelease(bundleV1Ids);
    awaitRelease(releaseRevisions());
    client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.components.policyRelease.status").isEqualTo("UP");
  }

  @Test
  @Order(3)
  void securityIsEnforcedBeforeAnyEvaluation() {
    client.post().uri("/api/v1/manifests").contentType(MediaType.APPLICATION_JSON)
        .bodyValue(manifest("development", List.of(), List.of())).exchange().expectStatus().isUnauthorized();
    submit(OUTSIDER, manifest("development", List.of(topic("team-a.orders", 6)), List.of()), "k-" + UUID.randomUUID())
        .expectStatus().isForbidden();
    submit(DEV, Map.of("teamId", "team-a", "repositoryId", "repo-1", "environment", "development",
        "resources", Map.of("topics", List.of(Map.of("name", "team-a.x", "partitions", 0)))), "k-" + UUID.randomUUID())
        .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("MANIFEST_INVALID");
  }

  @Test
  @Order(4)
  void deniedManifestReturnsStructuredViolations() {
    submit(DEV, manifest("development", List.of(topic("orders", 6)), List.of()), "deny-" + UUID.randomUUID())
        .expectStatus().isEqualTo(422)
        .expectBody()
        .jsonPath("$.status").isEqualTo("REJECTED")
        .jsonPath("$.violations[0].policyId").isEqualTo("AUTHORIZED_PREFIX_REQUIRED")
        .jsonPath("$.violations[0].enforcement").isEqualTo("enforce")
        .jsonPath("$.violations[0].bypassable").isEqualTo(true);
  }

  @Test
  @Order(5)
  void happyPathRunsThroughOrchestratorAndProcessor() {
    Map<String, Object> accepted = submitAccepted(DEV, manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3)), List.of()));
    String requestId = (String) accepted.get("requestId");
    assertThat(accepted).containsEntry("status", "ACCEPTED").containsEntry("statusUrl", "/api/v1/requests/" + requestId);

    Map<String, Object> done = awaitStatus(requestId, "COMPLETED");
    assertThat(done.get("planId")).isNotNull();
    assertThat(((Number) done.get("stateVersion")).longValue()).isEqualTo(1L);

    List<Map<String, Object>> decisions = decisions(requestId);
    assertThat(decisions).extracting(d -> d.get("stage") + ":" + d.get("type")).containsExactly("REQUEST:ALLOW", "ACTION_PLAN:ALLOW");

    client.get().uri("/api/v1/requests/" + requestId + "/plan").header("Authorization", "Bearer " + DEV).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.actions.length()").isEqualTo(2).jsonPath("$.actions[0].actionType").isEqualTo("TOPIC_CREATE");

    client.get().uri("/api/v1/requests/" + requestId).header("Authorization", "Bearer " + OUTSIDER).exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/requests/" + requestId).header("Authorization", "Bearer " + AUDITOR).exchange().expectStatus().isOk();
  }

  @Test
  @Order(6)
  void idempotencyKeyReplaysAndConflicts() {
    String key = "idem-" + UUID.randomUUID();
    Map<String, Object> m = manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3)), List.of());
    String first = (String) submit(DEV, m, key).expectStatus().isAccepted().expectBody(Map.class).returnResult().getResponseBody().get("requestId");
    String replay = (String) submit(DEV, m, key).expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody().get("requestId");
    assertThat(replay).isEqualTo(first);
    submit(DEV, manifest("development", List.of(topic("team-a.other", 1)), List.of()), key)
        .expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_CONFLICT");
    awaitStatus(first, "COMPLETED");
  }

  @Test
  @Order(7)
  void partialExecutionFailureIsReportedAndOnlySuccessesAreConfirmed() {
    String requestId = (String) submitAccepted(DEV, manifest("development",
        List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.fail.audit", 1)), List.of())).get("requestId");
    Map<String, Object> failed = awaitStatus(requestId, "FAILED");
    assertThat((String) failed.get("failureReason")).contains("PARTIAL_FAILURE");

    // Re-submitting the same desired state replans only the missing topic (confirmed state excludes the failed one).
    String retry = (String) submitAccepted(DEV, manifest("development",
        List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1)), List.of())).get("requestId");
    awaitStatus(retry, "COMPLETED");
    client.get().uri("/api/v1/requests/" + retry + "/plan").header("Authorization", "Bearer " + DEV).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.actions.length()").isEqualTo(1).jsonPath("$.actions[0].resourceName").isEqualTo("team-a.audit");
  }

  @Test
  @Order(8)
  void hardDenyCannotBeExemptedByAnException() {
    client.post().uri("/api/v1/admin/exceptions").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("policyId", "RBAC_ADMIN_ROLE_FORBIDDEN", "teamId", "team-a", "environment", "development", "resourcePattern", "*",
            "expiresAt", Instant.now().plus(Duration.ofDays(1)).toString(), "reason", "must not work")).exchange().expectStatus().isOk();
    submit(DEV, manifest("development", List.of(), List.of(Map.of("principal", "sa-1", "role", "CloudClusterAdmin", "resource", "cluster"))), "hard-" + UUID.randomUUID())
        .expectStatus().isEqualTo(422)
        .expectBody().jsonPath("$.violations[0].policyId").isEqualTo("RBAC_ADMIN_ROLE_FORBIDDEN")
        .jsonPath("$.violations[0].severity").isEqualTo("HARD_DENY").jsonPath("$.violations[0].exempted").isEqualTo(false);
  }

  @Test
  @Order(9)
  @SuppressWarnings("unchecked")
  void scopedExceptionExemptsBypassablePolicyAndRevocationRemovesIt() {
    Map<String, Object> created = client.post().uri("/api/v1/admin/exceptions").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("policyId", "AUTHORIZED_PREFIX_REQUIRED", "principalId", "dev-1", "teamId", "team-a", "environment", "development",
            "resourcePattern", "legacy.*", "expiresAt", Instant.now().plus(Duration.ofDays(1)).toString(), "reason", "migration"))
        .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    String exceptionId = (String) created.get("id");

    Map<String, Object> ok = submitAccepted(DEV, manifest("development",
        List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1), topic("legacy.events", 1)), List.of()));
    String requestId = (String) ok.get("requestId");
    awaitStatus(requestId, "COMPLETED");
    Map<String, Object> stage1 = decisions(requestId).get(0);
    assertThat((List<String>) stage1.get("appliedExceptions")).containsExactly(exceptionId);

    // Out of scope (different resource) still denied.
    submit(DEV, manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1),
        topic("legacy.events", 1), topic("other.events", 1)), List.of()), "scope-" + UUID.randomUUID())
        .expectStatus().isEqualTo(422);

    client.delete().uri("/api/v1/admin/exceptions/" + exceptionId).header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.revokedBy").isEqualTo("admin-1");
    submit(DEV, manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1), topic("legacy.events", 1)), List.of()),
        "revoked-" + UUID.randomUUID()).expectStatus().isEqualTo(422);
  }

  @Test
  @Order(10)
  @SuppressWarnings("unchecked")
  void productionTopicDeletionRequiresApprovalWithSeparationOfDuties() {
    String create = (String) submitAccepted(DEV, manifest("production", List.of(topic("team-a.orders", 6)), List.of())).get("requestId");
    awaitStatus(create, "COMPLETED");

    String delete = (String) submitAccepted(DEV, manifest("production", List.of(), List.of())).get("requestId");
    Map<String, Object> pending = awaitStatus(delete, "PENDING_APPROVAL_STAGE2");
    assertThat(decisions(delete).get(1)).containsEntry("type", "REQUIRE_APPROVAL");

    List<Map<String, Object>> approvals = client.get().uri("/api/v1/approvals?status=PENDING").header("Authorization", "Bearer " + APPROVER).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    Map<String, Object> approval = approvals.stream().filter(a -> delete.equals(a.get("requestId"))).findFirst().orElseThrow();
    String approvalId = (String) approval.get("id");
    assertThat(approval).containsEntry("planDigest", pending.get("planDigest")).containsEntry("stage", "ACTION_PLAN");

    client.get().uri("/api/v1/approvals?status=PENDING").header("Authorization", "Bearer " + DEV).exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/approvals?status=PENDING&teamId=team-a").header("Authorization", "Bearer " + DEV).exchange().expectStatus().isOk();

    client.post().uri("/api/v1/approvals/" + approvalId + "/decisions").header("Authorization", "Bearer " + DEV).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("verdict", "APPROVE")).exchange().expectStatus().isForbidden();
    client.post().uri("/api/v1/approvals/" + approvalId + "/decisions").header("Authorization", "Bearer " + DEV_WITH_APPROVER_ROLE).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("verdict", "APPROVE")).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectBody().jsonPath("$.message").value(m -> assertThat((String) m).contains("Separation of duties"));

    client.post().uri("/api/v1/approvals/" + approvalId + "/decisions").header("Authorization", "Bearer " + APPROVER).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("verdict", "APPROVE", "comment", "ok")).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.approval.status").isEqualTo("APPROVED").jsonPath("$.requestStatus").isEqualTo("PLAN_AUTHORIZED");
    // Idempotent replay of the same verdict.
    client.post().uri("/api/v1/approvals/" + approvalId + "/decisions").header("Authorization", "Bearer " + APPROVER).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("verdict", "APPROVE")).exchange().expectStatus().isOk();

    awaitStatus(delete, "COMPLETED");
    assertThat(decisions(delete)).hasSize(3); // stage 1, stage 2, revalidation on release

    List<Map<String, Object>> audit = client.get().uri("/api/v1/admin/audit?requestId=" + delete).header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(audit).extracting(a -> (String) a.get("action")).contains("APPROVAL_OPENED", "APPROVAL_DECISION", "APPROVAL_RELEASE_STAGE2", "REQUEST_COMPLETED");
    // Whatever implementation owns the approval, exactly one AUTHORIZE_PLAN left Governance.
    assertThat(jdbc.sql("SELECT count(*) FROM kafka_outbox WHERE request_id = :r AND headers->>'commandType' = 'AUTHORIZE_PLAN'").param("r", delete).query(Long.class).single()).isEqualTo(1L);
    Map<String, Object> resolved = client.get().uri("/api/v1/approvals/" + approvalId).header("Authorization", "Bearer " + APPROVER).exchange()
        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    Map<String, Object> approvalRow = (Map<String, Object>) resolved.get("approval");
    switch (workflow.mode()) {
      case FLOWABLE -> assertThat(approvalRow).containsEntry("engine", "FLOWABLE").hasEntrySatisfying("processInstanceId", v -> assertThat(v).isNotNull());
      case SHADOW -> {
        assertThat(approvalRow).containsEntry("engine", "LEGACY").hasEntrySatisfying("processInstanceId", v -> assertThat(v).isNotNull());
        assertThat(audit).filteredOn(a -> "WORKFLOW_SHADOW_OUTCOME".equals(a.get("action")))
            .singleElement().satisfies(a -> assertThat((Map<String, Object>) a.get("details")).containsEntry("engineOutcome", "APPROVED").containsEntry("match", true));
      }
      case LEGACY -> assertThat(approvalRow).containsEntry("engine", "LEGACY").containsEntry("processInstanceId", null);
    }
  }

  @Test
  @Order(11)
  void stageOneApprovalCanBeRejected() {
    String requestId = (String) submitAccepted(DEV, manifest("production", List.of(),
        List.of(Map.of("principal", "sa-1", "role", "DeveloperManage", "resource", "topic:team-a.orders")))).get("requestId");
    awaitStatus(requestId, "PENDING_APPROVAL_STAGE1");
    List<Map<String, Object>> approvals = client.get().uri("/api/v1/approvals?status=PENDING").header("Authorization", "Bearer " + APPROVER).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    String approvalId = (String) approvals.stream().filter(a -> requestId.equals(a.get("requestId"))).findFirst().orElseThrow().get("id");
    client.post().uri("/api/v1/approvals/" + approvalId + "/decisions").header("Authorization", "Bearer " + APPROVER).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("verdict", "REJECT", "comment", "no")).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.approval.status").isEqualTo("REJECTED").jsonPath("$.requestStatus").isEqualTo("STAGE1_DENIED");
  }

  @Test
  @Order(12)
  void replayedAndOutOfOrderEventsDoNotCorruptState() throws Exception {
    String requestId = (String) submitAccepted(DEV, manifest("development",
        List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1)), List.of())).get("requestId");
    Map<String, Object> done = awaitStatus(requestId, "COMPLETED");

    Map<String, Object> props = Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, PlatformContainers.KAFKA.getBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName(),
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, PlatformContainers.schemaRegistryUrl(),
        AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY, RecordNameStrategy.class.getName());
    String replayId = "replay-" + UUID.randomUUID();
    try (KafkaProducer<String, Object> p = new KafkaProducer<>(props)) {
      ManifestEvent stale = ManifestEvent.newBuilder().setEventId(replayId).setEventType(ManifestEventType.PLAN_PROPOSED)
          .setRequestId(requestId).setTeamId("team-a").setRepositoryId("repo-1").setEnvironment("development")
          .setPlanId((String) done.get("planId")).setPlanDigest((String) done.get("planDigest")).setStateVersion(0L).setOccurredAt(Instant.now())
          .setPayload(PlanProposedPayload.newBuilder().setActions(List.of()).setManifestDigest((String) done.get("manifestDigest")).build()).build();
      p.send(new ProducerRecord<>("response.manifest", "team-a", stale)).get();
      p.send(new ProducerRecord<>("response.manifest", "team-a", stale)).get(); // exact duplicate
    }
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
        jdbc.sql("SELECT status FROM kafka_inbox WHERE event_id = :e").param("e", replayId).query(String.class).optional()).contains("IGNORED"));
    assertThat(jdbc.sql("SELECT count(*) FROM kafka_inbox WHERE event_id = :e").param("e", replayId).query(Long.class).single()).isEqualTo(1L);
    assertThat(request(DEV, requestId)).containsEntry("status", "COMPLETED");
  }

  @Test
  @Order(13)
  @SuppressWarnings("unchecked")
  void policyConfigurationChangesApplyWithoutRedeployAndCanBeRolledBack() {
    List<Map<String, Object>> versions = client.get().uri("/api/v1/admin/policy-configurations").header("Authorization", "Bearer " + ADMIN).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    Map<String, Object> active = versions.stream().filter(v -> "ACTIVE".equals(v.get("status"))).findFirst().orElseThrow();
    Map<String, Object> content = new java.util.HashMap<>((Map<String, Object>) active.get("content"));
    Map<String, Object> naming = new java.util.HashMap<>((Map<String, Object>) content.get("naming"));
    naming.put("requireTeamPrefix", false);
    content.put("naming", naming);

    Map<String, Object> draft = client.post().uri("/api/v1/admin/policy-configurations").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("content", content, "comment", "relax prefix")).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    client.post().uri("/api/v1/admin/policy-configurations/" + draft.get("id") + "/activate").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk();

    Map<String, Object> m = manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1), topic("unprefixed.events", 1)), List.of());
    String requestId = (String) submitAccepted(DEV, m).get("requestId");
    awaitStatus(requestId, "COMPLETED");
    assertThat(((Number) decisions(requestId).get(0).get("configurationVersion")).longValue()).isEqualTo(((Number) draft.get("version")).longValue());

    client.post().uri("/api/v1/admin/policy-configurations/rollback").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.id").isEqualTo(active.get("id"));
    submit(DEV, manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1), topic("unprefixed.events", 1),
        topic("another.one", 1)), List.of()), "rb-" + UUID.randomUUID()).expectStatus().isEqualTo(422);
  }

  @Test
  @Order(14)
  @SuppressWarnings("unchecked")
  void bundleActivationAndRollbackAreVerifiedAgainstOpa() throws Exception {
    Map<String, String> v1 = releaseRevisions();
    byte[] v2 = Bundles.withRevision(Files.readAllBytes(PlatformContainers.bundle("naming")), "naming-it-v2", "naming");
    String v2Id = uploadBundle(v2, "naming v2");
    client.post().uri("/api/v1/admin/policy-bundles/" + v2Id + "/activate").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk();
    Map<String, String> expected = new java.util.TreeMap<>(v1);
    expected.put("naming", "naming-it-v2");
    awaitRelease(expected);

    String requestId = (String) submitAccepted(DEV, manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1)), List.of())).get("requestId");
    awaitStatus(requestId, "COMPLETED");
    Map<String, Object> decision = decisions(requestId).get(0);
    assertThat((String) decision.get("policyRevision")).startsWith("rel-");
    assertThat((Map<String, String>) decision.get("policyRevisions")).containsEntry("naming", "naming-it-v2").containsEntry("core", v1.get("core"));

    client.post().uri("/api/v1/admin/policy-bundles/rollback?bundle=naming").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.id").isEqualTo(bundleV1Ids.get("naming"));
    awaitRelease(v1);
  }

  @Test
  @Order(15)
  void opaOutageFailsClosedAndRecovers() {
    var docker = PlatformContainers.OPA.getDockerClient();
    docker.pauseContainerCmd(PlatformContainers.OPA.getContainerId()).exec();
    String key = "outage-" + UUID.randomUUID();
    Map<String, Object> m = manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1)), List.of());
    try {
      submit(DEV, m, key).expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE).expectBody().jsonPath("$.code").isEqualTo("POLICY_EVALUATION_UNAVAILABLE");
    } finally {
      docker.unpauseContainerCmd(PlatformContainers.OPA.getContainerId()).exec();
    }
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> submit(DEV, m, key).expectStatus().isAccepted());
    String requestId = (String) client.get().uri("/api/v1/admin/audit?action=&targetType=manifest_request").header("Authorization", "Bearer " + AUDITOR)
        .exchange().expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody().stream()
        .map(a -> (String) ((Map<?, ?>) a).get("requestId")).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    assertThat(requestId).isNotBlank();
  }

  @Test
  @Order(16)
  @SuppressWarnings("unchecked")
  void policyCatalogueIsServedFromTheActiveBundle() {
    client.get().uri("/api/v1/admin/policies").header("Authorization", "Bearer " + DEV).exchange().expectStatus().isForbidden();
    List<Map<String, Object>> catalogue = client.get().uri("/api/v1/admin/policies").header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(catalogue).hasSize(20);
    assertThat(catalogue).allSatisfy(p -> assertThat(p.get("bundle")).isIn("core", "manifests", "naming", "quotas", "resources", "changes"));
    Map<String, Object> hardDeny = catalogue.stream().filter(p -> "RBAC_ADMIN_ROLE_FORBIDDEN".equals(p.get("policyId"))).findFirst().orElseThrow();
    assertThat(hardDeny).containsEntry("severity", "HARD_DENY").containsEntry("bypassable", false);
    assertThat(catalogue.stream().filter(p -> Boolean.TRUE.equals(p.get("bypassable"))).count()).isEqualTo(16);
    assertThat(catalogue).extracting(p -> (String) p.get("policyId")).contains("AUTHORIZED_PREFIX_REQUIRED", "TEAM_QUOTA_EXCEEDED", "RESOURCE_OWNED_BY_OTHER_REPOSITORY").doesNotContain("TEAM_PREFIX_REQUIRED");
  }

  @Test
  @Order(17)
  void observabilityEndpointsExposeTheMetrics() {
    client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP")
        .jsonPath("$.components.workflowEngine.status").isEqualTo("UP")
        .jsonPath("$.components.workflowEngine.details.definitions.governance-action-plan-approval").isEqualTo("v1");
    String prometheus = client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    assertThat(prometheus).contains("governance_opa_evaluation_seconds").contains("governance_decisions_total")
        .contains("governance_outbox_pending").contains("governance_approvals_pending").contains("governance_policy_revision_match")
        .contains("governance_usage_snapshots_total").contains("governance_quota_reservations_open")
        .contains("governance_approvals_legacy_pending").contains("governance_workflow_jobs_deadletter").contains("governance_workflow_instances_active")
        .contains("kafka_consumer_");
  }

  @Test
  @Order(18)
  void aBundleOpaCannotLoadFailsClosedUntilRolledBack() throws Exception {
    Map<String, String> v1 = releaseRevisions();
    byte[] broken = Bundles.withBrokenPolicy(Files.readAllBytes(PlatformContainers.bundle("naming")), "naming-it-broken", "naming");
    String brokenId = uploadBundle(broken, "naming broken");
    client.post().uri("/api/v1/admin/policy-bundles/" + brokenId + "/activate").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk();

    // Governance now expects naming-it-broken while OPA keeps the previous revision: decisions fail closed, readiness is down.
    await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
        submit(DEV, manifest("development", List.of(topic("team-a.orders", 6)), List.of()), "broken-" + UUID.randomUUID())
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertThat(diagnostics()).containsEntry("match", false);
    client.get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        .expectBody().jsonPath("$.components.policyRelease.status").isEqualTo("DOWN");

    client.post().uri("/api/v1/admin/policy-bundles/rollback?bundle=naming").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk();
    awaitRelease(v1);
    submit(DEV, manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1)), List.of()), "recovered-" + UUID.randomUUID())
        .expectStatus().isAccepted();
  }

  // ---------------------------------------------------------------- phase 2: team configuration, usage, quotas, shadow mode

  @Test
  @Order(19)
  @SuppressWarnings("unchecked")
  void teamQuotaIsEnforcedJointlyAcrossRepositoriesFromConsumedUsage() {
    // The usage consumer has turned the Orchestrator's confirmed state (teams.repo.manifest) into a per-repository snapshot.
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      List<Map<String, Object>> repos = (List<Map<String, Object>>) teamUsage().get("repositories");
      Map<String, Object> dev = repos.stream().filter(r -> "repo-1".equals(r.get("repositoryId")) && "development".equals(r.get("environment"))).findFirst().orElseThrow();
      assertThat(((Number) dev.get("stateVersion")).longValue()).isGreaterThanOrEqualTo(1L);
      assertThat((List<String>) dev.get("topics")).containsExactlyInAnyOrder("team-a.orders", "team-a.payments", "team-a.audit");
      assertThat(((Map<String, Number>) dev.get("counts")).get("topics").intValue()).isEqualTo(3);
      assertThat(((Map<String, Number>) dev.get("counts")).get("topicPartitions").intValue()).isEqualTo(10); // 6 + 3 + 1 as declared
    });

    // Role boundaries: readers see, only admins write.
    client.get().uri("/api/v1/admin/teams/team-a").header("Authorization", "Bearer " + DEV).exchange().expectStatus().isForbidden();
    client.put().uri("/api/v1/admin/teams/team-a").header("Authorization", "Bearer " + AUDITOR).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("authorizedPrefixes", List.of("team-a."))).exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/admin/teams/team-a").header("Authorization", "Bearer " + AUDITOR).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.source").isEqualTo("default").jsonPath("$.version").isEqualTo(0).jsonPath("$.authorizedPrefixes[0]").isEqualTo("team-a.");
    client.put().uri("/api/v1/admin/teams/team-a").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("authorizedPrefixes", List.of("team-a."), "quotas", Map.of("partitions", 1))).exchange().expectStatus().isBadRequest();

    // Quota of 4 topics for the whole team: repo-1 already holds 3, so repo-2 may add one topic but not two.
    Map<String, Object> created = configureTeam(List.of("team-a."), Map.of("topics", 4), "quota for the IT");
    assertThat(created).containsEntry("version", 1).containsEntry("source", "governance");
    submit(DEV, manifest("repo-2", "development", List.of(topic("team-a.repo2.events", 1), topic("team-a.repo2.audit", 1)), List.of()), "quota-" + UUID.randomUUID())
        .expectStatus().isEqualTo(422)
        .expectBody().jsonPath("$.violations[0].policyId").isEqualTo("TEAM_QUOTA_EXCEEDED")
        .jsonPath("$.violations[0].resourceName").isEqualTo("topics")
        .jsonPath("$.violations[0].message").value(m -> assertThat((String) m).contains("would use 5 topics").contains("quota is 4"));
    String repo2 = (String) submitAccepted(DEV, manifest("repo-2", "development", List.of(topic("team-a.repo2.events", 1)), List.of())).get("requestId");
    awaitStatus(repo2, "COMPLETED");

    // repo-2 took the last slot: repo-1 can no longer grow, until an admin raises the quota (no restart involved).
    Map<String, Object> grown = manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1), topic("team-a.extra", 1)), List.of());
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        submit(DEV, grown, "quota-full-" + UUID.randomUUID()).expectStatus().isEqualTo(422).expectBody().jsonPath("$.violations[0].policyId").isEqualTo("TEAM_QUOTA_EXCEEDED"));
    assertThat(configureTeam(List.of("team-a."), Map.of("topics", 5), "one more")).containsEntry("version", 2);
    awaitStatus((String) submitAccepted(DEV, grown).get("requestId"), "COMPLETED");

    List<Map<String, Object>> history = client.get().uri("/api/v1/admin/teams/team-a/history").header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(history).extracting(h -> (String) h.get("action")).containsExactly("UPDATED", "CREATED");
    List<Map<String, Object>> audit = client.get().uri("/api/v1/admin/audit?targetType=team_configuration&targetId=team-a").header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(audit).extracting(a -> (String) a.get("action")).contains("TEAM_CONFIGURATION_CREATED", "TEAM_CONFIGURATION_UPDATED");
  }

  @Test
  @Order(20)
  @SuppressWarnings("unchecked")
  void authorizedPrefixChangeAppliesToTheNextEvaluationWithoutRestart() {
    configureTeam(List.of("team-a.", "shared."), Map.of(), "shared topics allowed");
    String requestId = (String) submitAccepted(DEV, manifest("repo-3", "development", List.of(topic("shared.events", 1)), List.of())).get("requestId");
    awaitStatus(requestId, "COMPLETED");
    assertThat((List<String>) decisions(requestId).get(0).get("matchedPolicies")).isEmpty();

    configureTeam(List.of("team-a."), Map.of(), "shared topics withdrawn");
    submit(DEV, manifest("repo-3", "development", List.of(topic("shared.events", 1), topic("shared.more", 1)), List.of()), "prefix-" + UUID.randomUUID())
        .expectStatus().isEqualTo(422)
        .expectBody().jsonPath("$.violations[0].policyId").isEqualTo("AUTHORIZED_PREFIX_REQUIRED")
        .jsonPath("$.violations[0].message").value(m -> assertThat((String) m).contains("team-a."));
    client.get().uri("/api/v1/admin/teams").header("Authorization", "Bearer " + AUDITOR).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$[0].teamId").isEqualTo("team-a").jsonPath("$[0].version").isEqualTo(4);
  }

  @Test
  @Order(21)
  @SuppressWarnings("unchecked")
  void shadowPoliciesNeverBlockButAreRecordedAsFindings() {
    Map<String, Object> content = activeConfigurationContent();
    content.put("enforcement", Map.of("AUTHORIZED_PREFIX_REQUIRED", "shadow"));
    activateConfiguration(content, "prefix rule in shadow mode");
    client.post().uri("/api/v1/admin/policy-configurations").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("content", withEnforcement(content, Map.of("AUTHORIZED_PREFIX_REQUIRED", "audit")), "comment", "bad mode")).exchange().expectStatus().isBadRequest();

    Map<String, Object> unprefixed = manifest("repo-3", "development", List.of(topic("shared.events", 1), topic("other.thing", 1)), List.of());
    String requestId = (String) submitAccepted(DEV, unprefixed).get("requestId");
    awaitStatus(requestId, "COMPLETED");
    Map<String, Object> stage1 = decisions(requestId).get(0);
    assertThat(stage1).containsEntry("type", "ALLOW");
    List<Map<String, Object>> violations = (List<Map<String, Object>>) stage1.get("violations");
    assertThat(violations).hasSize(2).allSatisfy(v -> assertThat(v).containsEntry("policyId", "AUTHORIZED_PREFIX_REQUIRED").containsEntry("enforcement", "shadow").containsEntry("exempted", false));

    client.get().uri("/api/v1/admin/shadow-findings").header("Authorization", "Bearer " + DEV).exchange().expectStatus().isForbidden();
    List<Map<String, Object>> findings = client.get().uri("/api/v1/admin/shadow-findings?policyId=AUTHORIZED_PREFIX_REQUIRED&teamId=team-a").header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(findings.stream().filter(f -> requestId.equals(f.get("requestId")))).hasSize(2)
        .allSatisfy(f -> assertThat(f).containsEntry("kind", "VIOLATION").containsEntry("stage", "REQUEST").containsEntry("environment", "development"));
    String prometheus = client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    assertThat(prometheus).contains("governance_shadow_findings_total{kind=\"VIOLATION\",policyId=\"AUTHORIZED_PREFIX_REQUIRED\"");

    client.post().uri("/api/v1/admin/policy-configurations/rollback").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk();
    submit(DEV, unprefixed, "enforced-again-" + UUID.randomUUID()).expectStatus().isEqualTo(422);
  }

  private static Map<String, Object> withEnforcement(Map<String, Object> content, Map<String, Object> enforcement) {
    Map<String, Object> copy = new java.util.HashMap<>(content);
    copy.put("enforcement", enforcement);
    return copy;
  }

  @Test
  @Order(22)
  @SuppressWarnings("unchecked")
  void concurrentApprovalsOfOneTeamAdmitExactlyOnePlanWithinTheQuota() throws Exception {
    // 128 partitions per team in production; each plan creates 128 (2 x 64) and needs approval (high impact threshold 128).
    configureTeam(List.of("team-a."), Map.of("topicPartitions", 128), "partition budget");
    String first = (String) submitAccepted(DEV, manifest("repo-p1", "production", List.of(topic("team-a.p1.a", 64), topic("team-a.p1.b", 64)), List.of())).get("requestId");
    String second = (String) submitAccepted(DEV, manifest("repo-p2", "production", List.of(topic("team-a.p2.a", 64), topic("team-a.p2.b", 64)), List.of())).get("requestId");
    awaitStatus(first, "PENDING_APPROVAL_STAGE2");
    awaitStatus(second, "PENDING_APPROVAL_STAGE2");
    List<Map<String, Object>> pending = client.get().uri("/api/v1/approvals?status=PENDING").header("Authorization", "Bearer " + APPROVER).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    String approvalFirst = (String) pending.stream().filter(a -> first.equals(a.get("requestId"))).findFirst().orElseThrow().get("id");
    String approvalSecond = (String) pending.stream().filter(a -> second.equals(a.get("requestId"))).findFirst().orElseThrow().get("id");

    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.function.Function<String, java.util.concurrent.CompletableFuture<String>> approve = id -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      Map<String, Object> body = client.post().uri("/api/v1/approvals/" + id + "/decisions").header("Authorization", "Bearer " + APPROVER)
          .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("verdict", "APPROVE", "comment", "race")).exchange()
          .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
      return (String) body.get("requestStatus");
    });
    var a = approve.apply(approvalFirst);
    var b = approve.apply(approvalSecond);
    go.countDown();
    List<String> outcomes = List.of(a.get(60, java.util.concurrent.TimeUnit.SECONDS), b.get(60, java.util.concurrent.TimeUnit.SECONDS));
    assertThat(outcomes).containsExactlyInAnyOrder("PLAN_AUTHORIZED", "PLAN_REJECTED");

    String admitted = "PLAN_AUTHORIZED".equals(outcomes.get(0)) ? first : second;
    String refused = admitted.equals(first) ? second : first;
    awaitStatus(admitted, "COMPLETED");
    List<Map<String, Object>> refusedDecisions = decisions(refused);
    Map<String, Object> last = refusedDecisions.get(refusedDecisions.size() - 1);
    assertThat(last).containsEntry("type", "DENY");
    assertThat((List<Map<String, Object>>) last.get("violations")).anySatisfy(v -> assertThat(v).containsEntry("policyId", "TEAM_QUOTA_EXCEEDED").containsEntry("resourceName", "topicPartitions"));
    assertThat(request(DEV, refused)).containsEntry("status", "PLAN_REJECTED");

    // The admitted plan's reservation was released on completion; the confirmed usage now carries the 128 partitions.
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      Map<String, Object> usage = teamUsage();
      assertThat((List<?>) usage.get("reservations")).isEmpty();
      Map<String, Map<String, Number>> current = (Map<String, Map<String, Number>>) usage.get("currentByEnvironment");
      assertThat(current.get("production").get("topicPartitions").intValue()).isEqualTo(128);
    });
    List<Map<String, Object>> audit = client.get().uri("/api/v1/admin/audit?requestId=" + refused).header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(audit).extracting(a2 -> (String) a2.get("action")).contains("APPROVAL_RELEASE_STAGE2");
  }

  @Test
  @Order(23)
  @SuppressWarnings("unchecked")
  void expiredStage2ApprovalTellsTheOrchestratorToDropThePlan() {
    configureTeam(List.of("team-a."), Map.of("topicPartitions", 4096), "room for the expiry scenario");
    String requestId = (String) submitAccepted(DEV, manifest("repo-expiry", "production", List.of(topic("team-a.exp.a", 64), topic("team-a.exp.b", 64)), List.of())).get("requestId");
    awaitStatus(requestId, "PENDING_APPROVAL_STAGE2");
    List<Map<String, Object>> pending = client.get().uri("/api/v1/approvals?status=PENDING").header("Authorization", "Bearer " + APPROVER).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    Map<String, Object> approval = pending.stream().filter(a -> requestId.equals(a.get("requestId"))).findFirst().orElseThrow();
    String approvalId = (String) approval.get("id");

    if (workflow.mode() == GovernanceProperties.Workflow.Engine.FLOWABLE) {
      // The deadline is the engine's boundary timer: run it now instead of waiting 72 hours.
      engineConfiguration.getAsyncExecutor().shutdown();
      try {
        Job timer = management.createTimerJobQuery().processInstanceId((String) approval.get("processInstanceId")).singleResult();
        management.moveTimerToExecutableJob(timer.getId());
        management.executeJob(timer.getId());
      } finally {
        engineConfiguration.getAsyncExecutor().start();
      }
    } else {
      // Legacy rows expire through the sweep (5 s in this suite) once their TTL has passed.
      jdbc.sql("UPDATE approval_request SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", approvalId).update();
    }
    awaitStatus(requestId, "APPROVAL_EXPIRED");
    client.get().uri("/api/v1/approvals/" + approvalId).header("Authorization", "Bearer " + APPROVER).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.approval.status").isEqualTo("EXPIRED");
    // REJECT_PLAN APPROVAL_EXPIRED left through the outbox and reached the Orchestrator stand-in, which dropped the plan.
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
        jdbc.sql("SELECT status FROM kafka_outbox WHERE request_id = :r AND headers->>'commandType' = 'REJECT_PLAN'").param("r", requestId).query(String.class).list())
        .containsExactly("SENT"));
    assertThat(jdbc.sql("SELECT payload->'payload'->>'com.itland.platform.avro.RejectPlanPayload' FROM kafka_outbox WHERE request_id = :r AND headers->>'commandType' = 'REJECT_PLAN'")
        .param("r", requestId).query(String.class).single()).contains("APPROVAL_EXPIRED");
    assertThat((List<?>) teamUsage().get("reservations")).noneMatch(r -> requestId.equals(((Map<String, Object>) r).get("requestId")));
    List<Map<String, Object>> audit = client.get().uri("/api/v1/admin/audit?requestId=" + requestId).header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(audit).extracting(a -> (String) a.get("action")).contains("APPROVAL_EXPIRED");
    // A late verdict is refused.
    client.post().uri("/api/v1/approvals/" + approvalId + "/decisions").header("Authorization", "Bearer " + APPROVER).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("verdict", "APPROVE")).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.code").isEqualTo("APPROVAL_RULE");
  }

  @Test
  @Order(24)
  @SuppressWarnings("unchecked")
  void replayedPlanProposedWhileAnApprovalIsPendingOpensNoSecondApproval() throws Exception {
    String requestId = (String) submitAccepted(DEV, manifest("repo-replay", "production", List.of(topic("team-a.rp.a", 64), topic("team-a.rp.b", 64)), List.of())).get("requestId");
    Map<String, Object> pending = awaitStatus(requestId, "PENDING_APPROVAL_STAGE2");

    Map<String, Object> props = Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, PlatformContainers.KAFKA.getBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName(),
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, PlatformContainers.schemaRegistryUrl(),
        AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY, RecordNameStrategy.class.getName());
    String replayId = "replay-pending-" + UUID.randomUUID();
    try (KafkaProducer<String, Object> p = new KafkaProducer<>(props)) {
      // Same plan, new event id: the inbox cannot deduplicate it, the lifecycle guard must.
      ManifestEvent again = ManifestEvent.newBuilder().setEventId(replayId).setEventType(ManifestEventType.PLAN_PROPOSED)
          .setRequestId(requestId).setTeamId("team-a").setRepositoryId("repo-replay").setEnvironment("production")
          .setPlanId((String) pending.get("planId")).setPlanDigest((String) pending.get("planDigest"))
          .setStateVersion(((Number) pending.getOrDefault("stateVersion", 0)).longValue()).setOccurredAt(Instant.now())
          .setPayload(PlanProposedPayload.newBuilder().setActions(List.of()).setManifestDigest((String) pending.get("manifestDigest")).build()).build();
      p.send(new ProducerRecord<>("response.manifest", "team-a", again)).get();
    }
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
        jdbc.sql("SELECT status FROM kafka_inbox WHERE event_id = :e").param("e", replayId).query(String.class).optional()).contains("IGNORED"));
    assertThat(jdbc.sql("SELECT count(*) FROM approval_request WHERE request_id = :r").param("r", requestId).query(Long.class).single()).isEqualTo(1L);
    assertThat(request(DEV, requestId)).containsEntry("status", "PENDING_APPROVAL_STAGE2");

    List<Map<String, Object>> approvals = client.get().uri("/api/v1/approvals?status=PENDING").header("Authorization", "Bearer " + APPROVER).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    String approvalId = (String) approvals.stream().filter(a -> requestId.equals(a.get("requestId"))).findFirst().orElseThrow().get("id");
    client.post().uri("/api/v1/approvals/" + approvalId + "/decisions").header("Authorization", "Bearer " + APPROVER).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("verdict", "APPROVE", "comment", "after replay")).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.requestStatus").isEqualTo("PLAN_AUTHORIZED");
    awaitStatus(requestId, "COMPLETED");
  }
}

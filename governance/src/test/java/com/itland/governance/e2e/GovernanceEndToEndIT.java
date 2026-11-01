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
  WebTestClient client;
  String bundleV1Id;

  @BeforeAll
  void setUp() {
    client = WebTestClient.bindToServer().baseUrl("http://localhost:" + PlatformContainers.GOVERNANCE_PORT)
        .responseTimeout(Duration.ofSeconds(60)).build();
  }

  // ---------------------------------------------------------------- helpers

  static Map<String, Object> manifest(String env, List<Map<String, Object>> topics, List<Map<String, Object>> rbac) {
    return Map.of("teamId", "team-a", "repositoryId", "repo-1", "environment", env,
        "resources", Map.of("topics", topics, "rbac", rbac, "identityPools", List.of()));
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

  void awaitOpaRevision(String revision) {
    try {
      await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(diagnostics()).containsEntry("opaRevision", revision).containsEntry("match", true));
    } catch (RuntimeException e) {
      System.err.println("==== OPA container logs ====\n" + PlatformContainers.opaLogs());
      throw e;
    }
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
  void adminUploadsAndActivatesTheBundleAndOpaAdoptsIt() throws Exception {
    byte[] bundle = Files.readAllBytes(PlatformContainers.BUNDLE);
    client.post().uri("/api/v1/admin/policy-bundles").header("Authorization", "Bearer " + DEV).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("bundleBase64", "AA==", "testReportJson", "[]")).exchange().expectStatus().isForbidden();

    bundleV1Id = uploadBundle(bundle, "v1");
    client.post().uri("/api/v1/admin/policy-bundles/" + bundleV1Id + "/activate").header("Authorization", "Bearer " + ADMIN).exchange()
        .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ACTIVE");
    String revision = Files.readString(PlatformContainers.POLICIES_DIR.resolve("dist/revision.txt")).trim();
    awaitOpaRevision(revision);
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
        .jsonPath("$.violations[0].policyId").isEqualTo("TEAM_PREFIX_REQUIRED")
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
        .bodyValue(Map.of("policyId", "TEAM_PREFIX_REQUIRED", "principalId", "dev-1", "teamId", "team-a", "environment", "development",
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
  void bundleActivationAndRollbackAreVerifiedAgainstOpa() throws Exception {
    byte[] v2 = Bundles.withRevision(Files.readAllBytes(PlatformContainers.BUNDLE), "it-v2");
    String v2Id = uploadBundle(v2, "v2");
    client.post().uri("/api/v1/admin/policy-bundles/" + v2Id + "/activate").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk();
    awaitOpaRevision("it-v2");

    String requestId = (String) submitAccepted(DEV, manifest("development", List.of(topic("team-a.orders", 6), topic("team-a.payments", 3), topic("team-a.audit", 1)), List.of())).get("requestId");
    awaitStatus(requestId, "COMPLETED");
    assertThat(decisions(requestId).get(0)).containsEntry("policyRevision", "it-v2");

    client.post().uri("/api/v1/admin/policy-bundles/rollback").header("Authorization", "Bearer " + ADMIN).exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.id").isEqualTo(bundleV1Id);
    awaitOpaRevision(Files.readString(PlatformContainers.POLICIES_DIR.resolve("dist/revision.txt")).trim());
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
  void observabilityEndpointsExposeTheMetrics() {
    client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
    String prometheus = client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    assertThat(prometheus).contains("governance_opa_evaluation_seconds").contains("governance_decisions_total")
        .contains("governance_outbox_pending").contains("governance_approvals_pending").contains("governance_policy_revision_match")
        .contains("kafka_consumer_");
  }
}

package com.itland.governance.e2e;

import com.itland.governance.support.PlatformContainers;
import com.itland.governance.support.TestJwt;
import com.itland.governance.support.TestSecurityConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The deployed topology (plan section 11) on the local stack: Governance in {@code release} mode with the policy
 * release manifest mounted as a file, OPA as a sidecar pulling the six pinned revisions from an Artifactory stand-in
 * (nginx, bearer token, immutable paths). Proves that a pod is not ready until every pinned bundle is active, that
 * nothing in Governance can change the set, and that decisions carry the manifest's release id once it is complete.
 * Unsigned local bundles are used; signature verification is exercised by the policy repository's publish smoke.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=openshift",
    "governance.opa.bundle-token=" + PlatformContainers.BUNDLE_TOKEN,
    "governance.opa.response-timeout=3s",
    "governance.opa.max-retries=1",
    "governance.opa.revision-check-interval=2s",
    "governance.kafka.requests-topic=requests.manifest.release-mode",
    "governance.kafka.response-topic=response.manifest.release-mode",
    "governance.kafka.consumer-group=governance-release-mode",
    "governance.kafka.consumer-enabled=false",
    "governance.kafka.usage-consumer-enabled=false",
    "governance.outbox.poll-interval=200ms",
    "logging.structured.format.console=",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer"})
@Import(TestSecurityConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PolicyReleaseModeIT {

  static final String ARTIFACTORY_TOKEN = "release-it-reader-token";
  static final String REPOSITORY = "opa-bundles";
  static final Path STANDIN_TEMPLATE = PlatformContainers.POLICIES_DIR.resolve("tests/integration/artifactory-standin/nginx.conf.template");
  static final Path WORK = Path.of("target", "release-mode-it").toAbsolutePath();
  static final Path ARTIFACTS = WORK.resolve("artifactory");
  static final Path RELEASE_FILE = WORK.resolve("release.json");
  static final String DATABASE = "governance_release_mode";

  static Map<String, String> revisions = new TreeMap<>();
  static String releaseId;

  static final GenericContainer<?> ARTIFACTORY = new GenericContainer<>("nginx:1.29-alpine")
      .withNetwork(PlatformContainers.NETWORK).withNetworkAliases("artifactory").withExposedPorts(8080)
      .withEnv("STANDIN_TOKEN", ARTIFACTORY_TOKEN)
      .withCopyFileToContainer(MountableFile.forHostPath(STANDIN_TEMPLATE), "/etc/nginx/templates/default.conf.template")
      .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200));

  static GenericContainer<?> opa;

  static {
    PlatformContainers.start();
    try {
      prepareArtifactsWithoutBundle("changes");
      ARTIFACTORY.withFileSystemBind(ARTIFACTS.toString(), "/data", BindMode.READ_WRITE).start();
      opa = new GenericContainer<>("openpolicyagent/opa:1.22.0")
          .withNetwork(PlatformContainers.NETWORK).withExposedPorts(8181)
          .withCopyToContainer(Transferable.of(opaConfig()), "/config/opa.yaml")
          .withCommand("run", "--server", "--addr", "0.0.0.0:8181", "--diagnostic-addr", "0.0.0.0:8282", "--config-file", "/config/opa.yaml", "--log-level", "info")
          .waitingFor(Wait.forHttp("/health").forPort(8181).forStatusCode(200));
      opa.start();
      createDatabase();
      createTopics();
    } catch (Exception e) {
      throw new IllegalStateException("release-mode fixtures failed", e);
    }
  }

  /** Lays out the local build as Artifactory would hold it: <repo>/<bundle>/<revision>/<revision>.tar.gz, all but one. */
  @SuppressWarnings("unchecked")
  static void prepareArtifactsWithoutBundle(String withheld) throws Exception {
    Map<String, Object> local = new tools.jackson.databind.json.JsonMapper().readValue(Files.readString(PlatformContainers.RELEASE), Map.class);
    Map<String, Map<String, Object>> bundles = (Map<String, Map<String, Object>>) local.get("bundles");
    bundles.forEach((name, b) -> revisions.put(name, (String) b.get("revision")));
    releaseId = (String) local.get("releaseId");
    Files.createDirectories(WORK);
    for (String name : revisions.keySet()) if (!name.equals(withheld)) publish(name);
    // The mounted manifest: what Governance expects, independent of what Artifactory currently serves.
    StringBuilder manifest = new StringBuilder("{\"releaseId\":\"" + releaseId + "\",\"source\":\"promotion\",\"bundles\":{");
    boolean first = true;
    for (var e : revisions.entrySet()) {
      if (!first) manifest.append(',');
      first = false;
      manifest.append('"').append(e.getKey()).append("\":{\"revision\":\"").append(e.getValue()).append("\",\"sha256\":\"")
          .append(bundles.get(e.getKey()).get("sha256")).append("\"}");
    }
    Files.writeString(RELEASE_FILE, manifest.append("}}").toString());
  }

  static void publish(String name) throws Exception {
    String revision = revisions.get(name);
    Path dir = ARTIFACTS.resolve(REPOSITORY).resolve(name).resolve(revision);
    Files.createDirectories(dir);
    Files.copy(PlatformContainers.bundle(name), dir.resolve(revision + ".tar.gz"), StandardCopyOption.REPLACE_EXISTING);
  }

  static String opaConfig() {
    StringBuilder b = new StringBuilder("""
        services:
          artifactory:
            url: http://artifactory:8080/artifactory/%s
            credentials:
              bearer:
                token: %s
        bundles:
        """.formatted(REPOSITORY, ARTIFACTORY_TOKEN));
    revisions.forEach((name, revision) -> b.append("  ").append(name).append(":\n    service: artifactory\n    resource: ")
        .append(name).append('/').append(revision).append('/').append(revision).append(".tar.gz\n    polling:\n      min_delay_seconds: 1\n      max_delay_seconds: 2\n"));
    b.append("decision_logs:\n  console: false\n");
    return b.toString();
  }

  static void createDatabase() throws Exception {
    try (var c = DriverManager.getConnection(PlatformContainers.POSTGRES.getJdbcUrl(), PlatformContainers.POSTGRES.getUsername(), PlatformContainers.POSTGRES.getPassword());
         var st = c.createStatement()) {
      st.execute("CREATE DATABASE " + DATABASE);
    } catch (java.sql.SQLException e) {
      if (!"42P04".equals(e.getSQLState())) throw e; // already exists
    }
  }

  static void createTopics() throws Exception {
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, PlatformContainers.KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic("requests.manifest.release-mode", 1, (short) 1), new NewTopic("response.manifest.release-mode", 1, (short) 1))).all().get();
    } catch (java.util.concurrent.ExecutionException e) {
      if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw e;
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> PlatformContainers.POSTGRES.getJdbcUrl().replaceAll("/" + PlatformContainers.POSTGRES.getDatabaseName() + "(\\?|$)", "/" + DATABASE + "$1"));
    r.add("spring.datasource.username", PlatformContainers.POSTGRES::getUsername);
    r.add("spring.datasource.password", PlatformContainers.POSTGRES::getPassword);
    r.add("governance.kafka.bootstrap-servers", PlatformContainers.KAFKA::getBootstrapServers);
    r.add("governance.kafka.schema-registry-url", PlatformContainers::schemaRegistryUrl);
    r.add("governance.opa.url", () -> "http://" + opa.getHost() + ":" + opa.getMappedPort(8181));
    r.add("governance.opa.release-file", RELEASE_FILE::toString);
  }

  static final String DEV = TestJwt.token("dev-1", List.of("team-a"), List.of("development", "production"), List.of());
  static final String ADMIN = TestJwt.token("admin-1", List.of(), List.of(), List.of("governance-admin"));
  static final String AUDITOR = TestJwt.token("auditor-1", List.of(), List.of(), List.of("governance-auditor"));

  @LocalServerPort int port;
  WebTestClient client;

  @BeforeAll
  void setUp() {
    client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(60)).build();
  }

  static Map<String, Object> manifest() {
    return Map.of("teamId", "team-a", "repositoryId", "repo-release", "environment", "development",
        "resources", Map.of("topics", List.of(Map.of("name", "team-a.release-mode", "partitions", 3, "config", Map.of())), "rbac", List.of(), "identityPools", List.of()));
  }

  WebTestClient.ResponseSpec submit() {
    return client.post().uri("/api/v1/manifests").header("Authorization", "Bearer " + DEV).header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).bodyValue(manifest()).exchange();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> current() {
    return client.get().uri("/api/v1/admin/policy-releases/current").header("Authorization", "Bearer " + AUDITOR).exchange()
        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  @Test
  @Order(1)
  @SuppressWarnings("unchecked")
  void notReadyWhileOnePinnedRevisionIsMissingFromArtifactory() {
    await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
      Map<String, Object> d = current();
      Map<String, Map<String, Object>> bundles = (Map<String, Map<String, Object>>) d.get("bundles");
      assertThat(bundles.get("core").get("loaded")).isEqualTo(revisions.get("core"));
      assertThat(bundles.get("changes").get("loaded")).isNull();
    });
    Map<String, Object> d = current();
    assertThat(d).containsEntry("mode", "release").containsEntry("releaseId", releaseId).containsEntry("match", false).containsEntry("ready", false);
    assertThat((String) d.get("source")).startsWith("file:").endsWith("release.json");
    client.get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        .expectBody().jsonPath("$.components.policyRelease.status").isEqualTo("DOWN").jsonPath("$.components.policyRelease.details.releaseId").isEqualTo(releaseId);
    submit().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE).expectBody().jsonPath("$.code").isEqualTo("POLICY_EVALUATION_UNAVAILABLE");
  }

  @Test
  @Order(2)
  void localPolicyAdministrationIsRefused() throws Exception {
    String bundle = Base64.getEncoder().encodeToString(Files.readAllBytes(PlatformContainers.bundle("naming")));
    String report = Files.readString(PlatformContainers.TEST_REPORT);
    client.post().uri("/api/v1/admin/policy-bundles").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("bundleBase64", bundle, "testReportJson", report, "comment", "release mode")).exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.code").isEqualTo("POLICY_SOURCE_IS_GIT");
    client.post().uri("/api/v1/admin/policy-bundles/rollback?bundle=naming").header("Authorization", "Bearer " + ADMIN).exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.code").isEqualTo("POLICY_SOURCE_IS_GIT");
    client.post().uri("/api/v1/admin/policy-releases").header("Authorization", "Bearer " + ADMIN).contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("versionIds", List.of("bundle-x"))).exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.code").isEqualTo("POLICY_SOURCE_IS_GIT");
    client.get().uri("/internal/opa/bundles/core").header("Authorization", "Bearer " + PlatformContainers.BUNDLE_TOKEN).exchange().expectStatus().isNotFound();
    client.get().uri("/api/v1/admin/policy-bundles").header("Authorization", "Bearer " + AUDITOR).exchange().expectStatus().isOk().expectBody().json("[]");
  }

  @Test
  @Order(3)
  @SuppressWarnings("unchecked")
  void readyOnceEverySixthPinnedRevisionIsActiveAndDecisionsCarryTheManifestReleaseId() throws Exception {
    publish("changes");
    await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.components.policyRelease.status").isEqualTo("UP"));
    Map<String, Object> d = current();
    assertThat(d).containsEntry("match", true).containsEntry("ready", true).containsEntry("releaseId", releaseId);
    assertThat(((Map<String, Map<String, Object>>) d.get("bundles")).values()).allSatisfy(b -> assertThat(b.get("match")).isEqualTo(true));

    String requestId = (String) submit().expectStatus().isAccepted().expectBody(Map.class).returnResult().getResponseBody().get("requestId");
    List<Map<String, Object>> decisions = client.get().uri("/api/v1/requests/" + requestId + "/decisions").header("Authorization", "Bearer " + DEV).exchange()
        .expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0)).containsEntry("policyRevision", releaseId).containsEntry("type", "ALLOW");
    assertThat((Map<String, String>) decisions.get(0).get("policyRevisions")).containsAllEntriesOf(revisions);
  }
}

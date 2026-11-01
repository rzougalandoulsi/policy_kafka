package com.itland.governance.support;

import com.itland.orchestratorsim.OrchestratorSimulator;
import com.itland.processorsim.ProcessorSimulator;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The whole platform for end-to-end tests: Kafka + Schema Registry + Postgres + OPA in containers, the
 * Orchestrator and Processor stand-ins in-process. Governance runs on a fixed port so OPA
 * (inside Docker) can pull bundles from it through host.testcontainers.internal.
 */
public final class PlatformContainers {

  private static final Logger log = LoggerFactory.getLogger(PlatformContainers.class);

  public static final int GOVERNANCE_PORT = 18080;
  public static final String BUNDLE_TOKEN = "test-bundle-token";
  public static final Path POLICIES_DIR = Path.of("..", "policies").toAbsolutePath().normalize();
  public static final Path BUNDLE = POLICIES_DIR.resolve("dist/governance-bundle.tar.gz");
  public static final Path TEST_REPORT = POLICIES_DIR.resolve("dist/test-report.json");

  public static final Network NETWORK = Network.newNetwork();

  public static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:8.0.0")
      .withNetwork(NETWORK).withNetworkAliases("kafka").withListener("kafka:19092");

  public static final GenericContainer<?> SCHEMA_REGISTRY = new GenericContainer<>("confluentinc/cp-schema-registry:8.0.0")
      .withNetwork(NETWORK).withNetworkAliases("schema-registry").withExposedPorts(8081)
      .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
      .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:19092")
      .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
      .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
      .dependsOn(KAFKA);

  public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("governance").withUsername("governance").withPassword("governance");

  private static final String OPA_CONFIG = """
      services:
        governance:
          url: http://host.docker.internal:%d/internal/opa
          credentials:
            bearer:
              token: %s
      bundles:
        governance:
          service: governance
          resource: bundles/governance
          polling:
            min_delay_seconds: 1
            max_delay_seconds: 2
      decision_logs:
        console: false
      """.formatted(GOVERNANCE_PORT, BUNDLE_TOKEN);

  public static final GenericContainer<?> OPA = new GenericContainer<>("openpolicyagent/opa:1.22.0")
      .withExposedPorts(8181)
      .withExtraHost("host.docker.internal", "host-gateway")
      .withCopyToContainer(Transferable.of(OPA_CONFIG), "/config.yaml")
      .withCommand("run", "--server", "--addr", "0.0.0.0:8181", "--config-file", "/config.yaml", "--log-level", "info")
      .waitingFor(Wait.forHttp("/health").forStatusCode(200));

  private static boolean started;
  private static OrchestratorSimulator orchestrator;
  private static ProcessorSimulator simulator;

  private PlatformContainers() {}

  public static synchronized void start() {
    if (started) return;
    ensureBundle();
    KAFKA.start();
    SCHEMA_REGISTRY.start();
    POSTGRES.start();
    OPA.start();
    createTopics();
    orchestrator = new OrchestratorSimulator(KAFKA.getBootstrapServers(), schemaRegistryUrl(), OrchestratorSimulator.Topics.defaults(), Map.of());
    orchestrator.start();
    simulator = new ProcessorSimulator(KAFKA.getBootstrapServers(), schemaRegistryUrl(), "teams.changes", "request.status",
        a -> a.getPayload().getOrDefault("name", "").startsWith("team-a.fail"));
    simulator.start();
    Runtime.getRuntime().addShutdownHook(new Thread(PlatformContainers::stop));
    started = true;
    log.info("Platform ready: kafka={} sr={} opa={}", KAFKA.getBootstrapServers(), schemaRegistryUrl(), opaUrl());
  }

  public static String schemaRegistryUrl() {
    return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
  }

  public static String opaLogs() {
    String logs = OPA.getLogs();
    return logs.length() > 6000 ? logs.substring(logs.length() - 6000) : logs;
  }

  public static String opaUrl() {
    return "http://" + OPA.getHost() + ":" + OPA.getMappedPort(8181);
  }

  private static void ensureBundle() {
    if (Files.exists(BUNDLE) && Files.exists(TEST_REPORT) && !bundleIsStale()) return;
    log.info("Building the OPA bundle with policies/build.sh");
    try {
      Process p = new ProcessBuilder(POLICIES_DIR.resolve("build.sh").toString(), "it-local").inheritIO().start();
      if (p.waitFor() != 0) throw new IllegalStateException("policies/build.sh failed");
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("Unable to build the policy bundle", e);
    }
  }

  /** A bundle older than any policy source would make the tests exercise stale Rego. */
  private static boolean bundleIsStale() {
    try (var sources = Files.list(POLICIES_DIR.resolve("governance"))) {
      var built = Files.getLastModifiedTime(BUNDLE);
      return sources.anyMatch(f -> {
        try {
          return Files.getLastModifiedTime(f).compareTo(built) > 0;
        } catch (IOException e) {
          return true;
        }
      });
    } catch (IOException e) {
      return true;
    }
  }

  private static void createTopics() {
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin.createTopics(List.of(
          new NewTopic("requests.manifest", 1, (short) 1),
          new NewTopic("response.manifest", 1, (short) 1),
          new NewTopic("teams.repo.manifest", 1, (short) 1).configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)),
          new NewTopic("teams.changes", 1, (short) 1),
          new NewTopic("request.status", 1, (short) 1),
          new NewTopic("teams", 1, (short) 1).configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)),
          new NewTopic("team-a.status", 1, (short) 1))).all().get();
    } catch (Exception e) {
      throw new IllegalStateException("Topic creation failed", e);
    }
  }

  public static synchronized void stop() {
    if (simulator != null) simulator.stop();
    if (orchestrator != null) orchestrator.stop();
  }
}

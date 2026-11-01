package com.itland.governance.application.policy;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.configuration.Json;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.opa.OpaClient;
import com.itland.governance.infrastructure.persistence.PolicyBundleRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PolicyBundleServiceTest {

  private final PolicyBundleService service = new PolicyBundleService(mock(PolicyBundleRepository.class), mock(AuditService.class),
      new Json(JsonMapper.builder().build()), Clock.systemUTC(), mock(OpaClient.class), new GovernanceMetrics(new SimpleMeterRegistry()),
      mock(TransactionTemplate.class), new GovernanceProperties(null,
          new GovernanceProperties.Opa("http://opa", "/v1/data/governance/decision", "governance", Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofMillis(1), true, "t"),
          null, new GovernanceProperties.Workflow(Duration.ofMinutes(15), Duration.ofHours(72), 3, Duration.ofSeconds(30)),
          new GovernanceProperties.Outbox(Duration.ofMillis(500), 100, 10, Duration.ofSeconds(2)), new GovernanceProperties.Inbox(200, Duration.ofSeconds(5))));

  static final Path BUNDLE = Path.of("..", "policies", "dist", "governance-bundle.tar.gz");
  static final Path REPORT = Path.of("..", "policies", "dist", "test-report.json");

  @Test
  void inspectsTheBuiltBundle() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(BUNDLE), "run policies/build.sh first");
    Map<String, Object> manifest = service.inspect(Files.readAllBytes(BUNDLE));
    assertThat(manifest).containsKey("revision");
    assertThat(String.valueOf(manifest.get("roots"))).contains("governance");
  }

  @Test
  void rejectsGarbage() {
    assertThatThrownBy(() -> service.inspect("not a tarball".getBytes())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesTestReport() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(REPORT), "run policies/build.sh first");
    Map<String, Object> summary = service.validateTestReport(Files.readString(REPORT));
    assertThat((Integer) summary.get("passed")).isGreaterThan(30);
    assertThatThrownBy(() -> service.validateTestReport("[{\"name\":\"test_x\",\"package\":\"p\",\"fail\":true}]"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failing");
    assertThatThrownBy(() -> service.validateTestReport("[]")).isInstanceOf(IllegalArgumentException.class);
  }
}

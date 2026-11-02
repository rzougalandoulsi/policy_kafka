package com.itland.governance.application.policy;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.configuration.Json;
import com.itland.governance.configuration.PolicyReleaseSourceConfig;
import com.itland.governance.domain.policy.PolicyRelease;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.opa.OpaClient;
import com.itland.governance.infrastructure.persistence.PolicyBundleRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
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
import static org.mockito.Mockito.when;

/** Release mode: the mounted manifest is the only source and local administration is refused; the openshift profile forbids local mode. */
class PolicyReleaseModeGuardTest {

  static final Json JSON = new Json(JsonMapper.builder().build());

  static GovernanceProperties.Opa opa(String mode, String file) {
    return new GovernanceProperties.Opa("http://opa", "/v1/data/governance/core/decision", "/v1/data/governance/core/policy_catalog", "/v1/data/governance/core/ready",
        List.of("core", "manifests", "naming", "quotas", "resources", "changes"), Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofMillis(1), true, "t", "2.0",
        List.of("1", "2"), mode, file);
  }

  static GovernanceProperties properties(GovernanceProperties.Opa opa) {
    return new GovernanceProperties(null, opa, null, new GovernanceProperties.Workflow(Duration.ofMinutes(15), Duration.ofHours(72), 3, Duration.ofSeconds(30)),
        new GovernanceProperties.Outbox(Duration.ofMillis(500), 100, 10, Duration.ofSeconds(2)), new GovernanceProperties.Inbox(200, Duration.ofSeconds(5)),
        new GovernanceProperties.Usage(true));
  }

  @Test
  void localModeIsRefusedUnderTheDeployedProfile() {
    PolicyBundleRepository repository = mock(PolicyBundleRepository.class);
    assertThatThrownBy(() -> PolicyReleaseSourceConfig.create(opa("local", null), repository, JSON, true))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("openshift");
    assertThat(PolicyReleaseSourceConfig.create(opa("local", null), repository, JSON, false)).isInstanceOf(LocalPolicyReleaseSource.class);
  }

  @Test
  void releaseModeNeedsAFileAndRejectsUnknownModes() {
    PolicyBundleRepository repository = mock(PolicyBundleRepository.class);
    assertThatThrownBy(() -> PolicyReleaseSourceConfig.create(opa("release", " "), repository, JSON, true))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("release-file");
    assertThatThrownBy(() -> PolicyReleaseSourceConfig.create(opa("git", null), repository, JSON, false))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("local");
    assertThat(PolicyReleaseSourceConfig.create(opa("release", "/etc/governance/release.json"), repository, JSON, true)).isInstanceOf(FilePolicyReleaseSource.class);
  }

  @Test
  void releaseModeRefusesLocalAdministrationAndDiagnosesAgainstTheFile(@TempDir Path dir) throws Exception {
    Map<String, String> revisions = Map.of("core", "core-1", "manifests", "manifests-1", "naming", "naming-1", "quotas", "quotas-1", "resources", "resources-1", "changes", "changes-1");
    PolicyRelease release = PolicyRelease.of(revisions);
    Path file = dir.resolve("release.json");
    Files.writeString(file, FilePolicyReleaseSourceTest.manifest(revisions, release.id()));
    PolicyBundleRepository repository = mock(PolicyBundleRepository.class);
    OpaClient opaClient = mock(OpaClient.class);
    Map<String, String> loaded = new java.util.TreeMap<>(revisions);
    loaded.put("changes", "changes-0");
    when(opaClient.loadedRevisions()).thenReturn(Mono.just(loaded));
    when(opaClient.ready()).thenReturn(Mono.just(true));
    PolicyReleaseSource source = new FilePolicyReleaseSource(file, JSON);
    PolicyBundleService service = new PolicyBundleService(repository, mock(AuditService.class), JSON, Clock.systemUTC(), opaClient,
        new GovernanceMetrics(new SimpleMeterRegistry()), mock(TransactionTemplate.class), properties(opa("release", file.toString())), source);

    assertThatThrownBy(() -> service.upload(new byte[0], "[]", "c", "admin")).isInstanceOf(PolicySourceIsGitException.class);
    assertThatThrownBy(() -> service.activate("bundle-1", "admin")).isInstanceOf(PolicySourceIsGitException.class);
    assertThatThrownBy(() -> service.rollback("naming", "admin")).isInstanceOf(PolicySourceIsGitException.class);
    assertThatThrownBy(() -> service.activateRelease(List.of("a"), "admin")).isInstanceOf(PolicySourceIsGitException.class);
    assertThat(service.serveActive("core")).isEmpty();

    PolicyBundleService.RevisionDiagnostics d = service.diagnostics();
    assertThat(d.mode()).isEqualTo("release");
    assertThat(d.source()).startsWith("file:");
    assertThat(d.releaseId()).isEqualTo(release.id());
    assertThat(d.match()).isFalse();
    assertThat(d.bundles().get("changes").expected()).isEqualTo("changes-1");
    assertThat(d.bundles().get("changes").loaded()).isEqualTo("changes-0");
    assertThat(d.bundles().get("core").match()).isTrue();

    loaded.put("changes", "changes-1");
    assertThat(service.diagnostics().match()).isTrue();
  }
}

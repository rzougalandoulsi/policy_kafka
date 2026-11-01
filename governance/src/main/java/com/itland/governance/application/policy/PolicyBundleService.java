package com.itland.governance.application.policy;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.configuration.Json;
import com.itland.governance.domain.policy.PolicyBundleVersion;
import com.itland.governance.domain.policy.VersionStatus;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.opa.OpaClient;
import com.itland.governance.infrastructure.persistence.PolicyBundleRepository;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Controlled distribution of Rego logic. Bundles are built and tested outside the JVM (policies/build.sh runs
 * `opa check`, `opa test`, `opa build`); the resulting tarball plus its test report are uploaded here, validated,
 * versioned, and the ACTIVE one is served to OPA at /internal/opa/bundles/{name}. Activation is verified against the
 * revision OPA reports; if OPA does not adopt it within the grace period the version is marked FAILED and the previous
 * version is restored automatically.
 */
@Service
public class PolicyBundleService {

  private static final Logger log = LoggerFactory.getLogger(PolicyBundleService.class);
  private static final Duration ACTIVATION_GRACE = Duration.ofSeconds(90);

  private final PolicyBundleRepository repository;
  private final AuditService audit;
  private final Json json;
  private final Clock clock;
  private final OpaClient opa;
  private final GovernanceMetrics metrics;
  private final TransactionTemplate tx;
  private final String bundleName;

  public PolicyBundleService(PolicyBundleRepository repository, AuditService audit, Json json, Clock clock, OpaClient opa,
                             GovernanceMetrics metrics, TransactionTemplate tx, GovernanceProperties properties) {
    this.repository = repository;
    this.audit = audit;
    this.json = json;
    this.clock = clock;
    this.opa = opa;
    this.metrics = metrics;
    this.tx = tx;
    this.bundleName = properties.opa().bundleName();
  }

  public record Served(byte[] bytes, String revision, String sha256) {}

  public List<PolicyBundleVersion> list() {
    return repository.list();
  }

  public PolicyBundleVersion get(String id) {
    return repository.findById(id).orElseThrow(() -> new NotFoundException("Policy bundle " + id + " not found"));
  }

  public Optional<Served> serveActive() {
    return repository.findActive().flatMap(v -> repository.loadBytes(v.id()).map(b -> new Served(b, v.revision(), v.sha256())));
  }

  public PolicyBundleVersion upload(byte[] bundle, String testReportJson, String comment, String actor) {
    Map<String, Object> manifest = inspect(bundle);
    String revision = String.valueOf(manifest.get("revision"));
    if (revision.isBlank() || "null".equals(revision)) {
      throw new IllegalArgumentException("Bundle .manifest must declare a revision");
    }
    if (repository.findByRevision(revision).isPresent()) {
      throw new IllegalArgumentException("A bundle with revision " + revision + " already exists");
    }
    Map<String, Object> report = validateTestReport(testReportJson);
    String sha = sha256(bundle);
    String id = "bundle-" + UUID.randomUUID();
    repository.insert(id, revision, sha, bundle, manifest, report, comment, actor, clock.instant());
    audit.record(actor, "POLICY_BUNDLE_UPLOADED", "policy_bundle", id, Map.of("revision", revision, "sha256", sha, "tests", report.get("passed")));
    return get(id);
  }

  public PolicyBundleVersion activate(String id, String actor) {
    PolicyBundleVersion target = get(id);
    if (target.status() == VersionStatus.ACTIVE) return target;
    if (target.status() == VersionStatus.FAILED) {
      throw new IllegalArgumentException("Bundle " + id + " previously failed activation: " + target.failureReason());
    }
    if (target.testReport() == null) {
      throw new IllegalArgumentException("Bundle " + id + " has no passing test report attached");
    }
    repository.activate(id, clock.instant());
    audit.record(actor, "POLICY_BUNDLE_ACTIVATED", "policy_bundle", id, Map.of("revision", target.revision()));
    return get(id);
  }

  public PolicyBundleVersion rollback(String actor) {
    PolicyBundleVersion previous = repository.list().stream()
        .filter(v -> v.status() == VersionStatus.RETIRED && v.retiredAt() != null)
        .max(Comparator.comparing(PolicyBundleVersion::retiredAt))
        .orElseThrow(() -> new IllegalStateException("No retired bundle to roll back to"));
    repository.activate(previous.id(), clock.instant());
    audit.record(actor, "POLICY_BUNDLE_ROLLED_BACK", "policy_bundle", previous.id(), Map.of("revision", previous.revision()));
    return get(previous.id());
  }

  public record RevisionDiagnostics(String activeRevision, String opaRevision, boolean match) {}

  /** Diagnostics: the revision Governance expects versus what OPA runs. Blocking (worker/scheduler threads only). */
  public RevisionDiagnostics diagnostics() {
    String active = repository.findActive().map(PolicyBundleVersion::revision).orElse(null);
    String loaded;
    try {
      loaded = opa.loadedRevision().block(Duration.ofSeconds(5));
    } catch (RuntimeException e) {
      loaded = null;
    }
    boolean match = active != null && active.equals(loaded);
    metrics.policyRevisionMatch(active == null && loaded == null ? null : match);
    return new RevisionDiagnostics(active, loaded, match);
  }

  /** Verifies OPA adopted the active bundle; rolls back a version OPA could not load. */
  @Scheduled(fixedDelayString = "${governance.opa.revision-check-interval:15s}", initialDelayString = "15s")
  public void verifyActivation() {
    try {
      RevisionDiagnostics d = diagnostics();
      Optional<PolicyBundleVersion> active = repository.findActive();
      if (active.isEmpty() || d.match()) return;
      Instant activatedAt = active.get().activatedAt();
      if (activatedAt != null && Duration.between(activatedAt, clock.instant()).compareTo(ACTIVATION_GRACE) > 0 && d.opaRevision() != null) {
        log.error("OPA did not adopt bundle revision {} (still running {}); marking FAILED and restoring previous version", d.activeRevision(), d.opaRevision());
        tx.executeWithoutResult(s -> {
          repository.markFailed(active.get().id(), "OPA did not load revision; OPA reports " + d.opaRevision(), clock.instant());
          audit.record("system", "POLICY_BUNDLE_ACTIVATION_FAILED", "policy_bundle", active.get().id(), Map.of("opaRevision", d.opaRevision()));
          repository.list().stream().filter(v -> v.status() == VersionStatus.RETIRED && v.retiredAt() != null)
              .max(Comparator.comparing(PolicyBundleVersion::retiredAt))
              .ifPresent(prev -> {
                repository.activate(prev.id(), clock.instant());
                audit.record("system", "POLICY_BUNDLE_ROLLED_BACK", "policy_bundle", prev.id(), Map.of("revision", prev.revision(), "automatic", true));
              });
        });
      } else {
        log.warn("OPA bundle revision mismatch: active={} opa={}", d.activeRevision(), d.opaRevision());
      }
    } catch (RuntimeException e) {
      log.warn("Bundle revision verification failed: {}", e.toString());
    }
  }

  /** Reads the tar.gz, validates structure, returns the parsed .manifest. */
  Map<String, Object> inspect(byte[] bundle) {
    Map<String, Object> manifest = null;
    List<String> regoFiles = new ArrayList<>();
    try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(bundle)))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        String name = entry.getName().startsWith("/") ? entry.getName().substring(1) : entry.getName();
        if (name.contains("..")) throw new IllegalArgumentException("Bundle entry escapes the archive: " + name);
        if (entry.isDirectory()) continue;
        byte[] content = tar.readAllBytes();
        if (name.equals(".manifest")) {
          manifest = json.readMap(new String(content, StandardCharsets.UTF_8));
        } else if (name.endsWith(".rego")) {
          regoFiles.add(name);
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Bundle is not a valid gzip tar archive: " + e.getMessage(), e);
    }
    if (manifest == null) throw new IllegalArgumentException("Bundle has no .manifest (build it with `opa build -r <revision>`)");
    if (regoFiles.isEmpty()) throw new IllegalArgumentException("Bundle contains no .rego files");
    Object roots = manifest.get("roots");
    if (roots instanceof List<?> r && !r.contains("governance")) {
      throw new IllegalArgumentException("Bundle roots must include 'governance', got " + r);
    }
    return manifest;
  }

  /** `opa test --format=json` output: every entry must be a pass. Returns a summary map. */
  Map<String, Object> validateTestReport(String reportJson) {
    if (reportJson == null || reportJson.isBlank()) {
      throw new IllegalArgumentException("A test report (opa test --format=json) is required");
    }
    List<Map<String, Object>> entries = json.readListOfMaps(reportJson);
    if (entries.isEmpty()) throw new IllegalArgumentException("Test report contains no tests");
    List<String> failed = entries.stream()
        .filter(e -> Boolean.TRUE.equals(e.get("fail")) || Boolean.TRUE.equals(e.get("skip")) || e.get("error") != null)
        .map(e -> String.valueOf(e.get("package")) + "." + e.get("name")).toList();
    if (!failed.isEmpty()) throw new IllegalArgumentException("Test report has failing/skipped tests: " + failed);
    return Map.of("passed", entries.size(), "names", entries.stream().map(e -> String.valueOf(e.get("name"))).toList());
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public String bundleName() {
    return bundleName;
  }
}

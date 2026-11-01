package com.itland.governance.application.policy;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.configuration.Json;
import com.itland.governance.domain.policy.PolicyBundleVersion;
import com.itland.governance.domain.policy.PolicyRelease;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Controlled distribution of Rego logic in local mode. Six bundles (one per root governance/&lt;name&gt;) are built and
 * tested outside the JVM (policies/build.sh runs `opa check`, `opa test`, `opa build` per bundle); each tarball plus the
 * shared test report is uploaded here, validated against its declared root, versioned per bundle name, and the ACTIVE
 * version of every name is served to OPA at /internal/opa/bundles/{name}. The set of ACTIVE versions is the policy
 * release ({@link PolicyRelease}); every decision records its id and the per-bundle revisions OPA reported.
 * Activation is verified per bundle against the revision OPA reports; a version OPA does not adopt within the grace
 * period is marked FAILED and the previous version of that bundle is restored automatically.
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
  private final List<String> bundles;

  public PolicyBundleService(PolicyBundleRepository repository, AuditService audit, Json json, Clock clock, OpaClient opa,
                             GovernanceMetrics metrics, TransactionTemplate tx, GovernanceProperties properties) {
    this.repository = repository;
    this.audit = audit;
    this.json = json;
    this.clock = clock;
    this.opa = opa;
    this.metrics = metrics;
    this.tx = tx;
    this.bundles = List.copyOf(properties.opa().bundles());
  }

  public record Served(byte[] bytes, String revision, String sha256) {}

  /** Bundle names OPA must run. */
  public List<String> bundles() {
    return bundles;
  }

  public List<PolicyBundleVersion> list() {
    return repository.list();
  }

  public PolicyBundleVersion get(String id) {
    return repository.findById(id).orElseThrow(() -> new NotFoundException("Policy bundle " + id + " not found"));
  }

  public Optional<Served> serveActive(String bundleName) {
    return repository.findActive(bundleName).flatMap(v -> repository.loadBytes(v.id()).map(b -> new Served(b, v.revision(), v.sha256())));
  }

  /** The current release: ACTIVE revision per bundle name, or empty when nothing is active. */
  public Optional<PolicyRelease> activeRelease() {
    Map<String, String> revisions = new TreeMap<>();
    for (PolicyBundleVersion v : repository.findActiveAll()) revisions.put(v.bundleName(), v.revision());
    return revisions.isEmpty() ? Optional.empty() : Optional.of(PolicyRelease.of(revisions));
  }

  public PolicyBundleVersion upload(byte[] bundle, String testReportJson, String comment, String actor) {
    Map<String, Object> manifest = inspect(bundle);
    String name = bundleNameOf(manifest);
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
    repository.insert(id, name, revision, sha, bundle, manifest, report, comment, actor, clock.instant());
    audit.record(actor, "POLICY_BUNDLE_UPLOADED", "policy_bundle", id, Map.of("bundle", name, "revision", revision, "sha256", sha, "tests", report.get("passed")));
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
    audit.record(actor, "POLICY_BUNDLE_ACTIVATED", "policy_bundle", id, Map.of("bundle", target.bundleName(), "revision", target.revision()));
    return get(id);
  }

  /**
   * Activates a complete release: exactly one version per required bundle name, all in one transaction (the caller
   * wraps this in a transaction), so OPA never observes a half-switched set that Governance would also reject.
   */
  public PolicyRelease activateRelease(List<String> versionIds, String actor) {
    if (versionIds == null || versionIds.isEmpty()) throw new IllegalArgumentException("versionIds is required");
    Map<String, PolicyBundleVersion> byName = new LinkedHashMap<>();
    for (String id : new LinkedHashSet<>(versionIds)) {
      PolicyBundleVersion v = get(id);
      if (byName.put(v.bundleName(), v) != null) throw new IllegalArgumentException("Two versions given for bundle " + v.bundleName());
    }
    Set<String> missing = new java.util.TreeSet<>(bundles);
    missing.removeAll(byName.keySet());
    if (!missing.isEmpty()) throw new IllegalArgumentException("A release needs one version per bundle; missing " + missing);
    for (PolicyBundleVersion v : byName.values()) activate(v.id(), actor);
    PolicyRelease release = activeRelease().orElseThrow();
    audit.record(actor, "POLICY_RELEASE_ACTIVATED", "policy_release", release.id(), new TreeMap<>(release.revisions()));
    return release;
  }

  /** Restores the most recently retired version of one bundle. */
  public PolicyBundleVersion rollback(String bundleName, String actor) {
    requireKnown(bundleName);
    PolicyBundleVersion previous = repository.latestRetired(bundleName)
        .orElseThrow(() -> new IllegalStateException("No retired version of bundle " + bundleName + " to roll back to"));
    repository.activate(previous.id(), clock.instant());
    audit.record(actor, "POLICY_BUNDLE_ROLLED_BACK", "policy_bundle", previous.id(), Map.of("bundle", bundleName, "revision", previous.revision()));
    return get(previous.id());
  }

  public record BundleDiagnostics(String expected, String loaded, boolean match) {}

  /** Expected release versus what OPA runs, per bundle. {@code match} is true only when every required bundle agrees. */
  public record RevisionDiagnostics(String releaseId, boolean match, boolean ready, Map<String, BundleDiagnostics> bundles) {}

  /** Blocking (worker/scheduler threads only). */
  public RevisionDiagnostics diagnostics() {
    Optional<PolicyRelease> release = activeRelease();
    Map<String, String> loaded;
    boolean ready;
    try {
      loaded = opa.loadedRevisions().block(Duration.ofSeconds(5));
      ready = Boolean.TRUE.equals(opa.ready().block(Duration.ofSeconds(5)));
    } catch (RuntimeException e) {
      loaded = Map.of();
      ready = false;
    }
    if (loaded == null) loaded = Map.of();
    Map<String, BundleDiagnostics> perBundle = new TreeMap<>();
    boolean allMatch = release.isPresent();
    for (String name : bundles) {
      String expected = release.map(r -> r.revisions().get(name)).orElse(null);
      String actual = loaded.get(name);
      boolean match = expected != null && expected.equals(actual);
      allMatch &= match;
      perBundle.put(name, new BundleDiagnostics(expected, actual, match));
    }
    boolean nothingAnywhere = release.isEmpty() && loaded.isEmpty();
    metrics.policyRevisionMatch(nothingAnywhere ? null : allMatch);
    return new RevisionDiagnostics(release.map(PolicyRelease::id).orElse(null), allMatch, ready, perBundle);
  }

  /** Verifies OPA adopted every active bundle; rolls back a version OPA could not load within the grace period. */
  @Scheduled(fixedDelayString = "${governance.opa.revision-check-interval:15s}", initialDelayString = "15s")
  public void verifyActivation() {
    try {
      RevisionDiagnostics d = diagnostics();
      if (d.match()) return;
      for (Map.Entry<String, BundleDiagnostics> e : d.bundles().entrySet()) {
        String name = e.getKey();
        BundleDiagnostics b = e.getValue();
        if (b.expected() == null || b.match()) continue;
        Optional<PolicyBundleVersion> active = repository.findActive(name);
        if (active.isEmpty()) continue;
        Instant activatedAt = active.get().activatedAt();
        boolean graceOver = activatedAt != null && Duration.between(activatedAt, clock.instant()).compareTo(ACTIVATION_GRACE) > 0;
        if (graceOver && b.loaded() != null) {
          log.error("OPA did not adopt bundle {} revision {} (still running {}); marking FAILED and restoring previous version", name, b.expected(), b.loaded());
          tx.executeWithoutResult(s -> {
            repository.markFailed(active.get().id(), "OPA did not load revision; OPA reports " + b.loaded(), clock.instant());
            audit.record("system", "POLICY_BUNDLE_ACTIVATION_FAILED", "policy_bundle", active.get().id(), Map.of("bundle", name, "opaRevision", b.loaded()));
            repository.latestRetired(name).ifPresent(prev -> {
              repository.activate(prev.id(), clock.instant());
              audit.record("system", "POLICY_BUNDLE_ROLLED_BACK", "policy_bundle", prev.id(), Map.of("bundle", name, "revision", prev.revision(), "automatic", true));
            });
          });
        } else {
          log.warn("OPA bundle revision mismatch for {}: active={} opa={}", name, b.expected(), b.loaded());
        }
      }
    } catch (RuntimeException e) {
      log.warn("Bundle revision verification failed: {}", e.toString());
    }
  }

  /** Reads the tar.gz, validates structure and root, returns the parsed .manifest. */
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
    bundleNameOf(manifest);
    return manifest;
  }

  /** A bundle must declare exactly one root, governance/&lt;name&gt;, for a known bundle name. */
  String bundleNameOf(Map<String, Object> manifest) {
    if (!(manifest.get("roots") instanceof List<?> roots) || roots.size() != 1) {
      throw new IllegalArgumentException("Bundle .manifest must declare exactly one root governance/<bundle>, got " + manifest.get("roots"));
    }
    String root = String.valueOf(roots.get(0));
    if (!root.startsWith("governance/")) throw new IllegalArgumentException("Bundle root must be governance/<bundle>, got " + root);
    String name = root.substring("governance/".length());
    requireKnown(name);
    return name;
  }

  private void requireKnown(String name) {
    if (!bundles.contains(name)) throw new IllegalArgumentException("Unknown bundle '" + name + "'; expected one of " + bundles);
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
}

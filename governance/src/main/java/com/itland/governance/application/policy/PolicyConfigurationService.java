package com.itland.governance.application.policy;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.domain.policy.PolicyBundleVersion;
import com.itland.governance.domain.policy.PolicyConfiguration;
import com.itland.governance.domain.policy.PolicySnapshot;
import com.itland.governance.domain.policy.VersionStatus;
import com.itland.governance.infrastructure.persistence.PolicyBundleRepository;
import com.itland.governance.infrastructure.persistence.PolicyConfigurationRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime policy parameters (naming patterns, roles, quotas, approval switches). Versioned; exactly one ACTIVE.
 * All methods are blocking and expected to run on the blocking scheduler / worker threads.
 */
@Service
public class PolicyConfigurationService {

  static final Set<String> REQUIRED_SECTIONS = Set.of("naming", "rbac", "limits", "destructive", "approvals");

  private final PolicyConfigurationRepository configurations;
  private final PolicyBundleRepository bundles;
  private final AuditService audit;
  private final Clock clock;

  public PolicyConfigurationService(PolicyConfigurationRepository configurations, PolicyBundleRepository bundles, AuditService audit, Clock clock) {
    this.configurations = configurations;
    this.bundles = bundles;
    this.audit = audit;
    this.clock = clock;
  }

  /** The consistent (configuration version, bundle revision) pair for one decision. Bundle revision is null when none is active. */
  public PolicySnapshot activeSnapshot() {
    PolicyConfiguration config = configurations.findActive()
        .orElseThrow(() -> new IllegalStateException("No ACTIVE policy configuration"));
    String revision = bundles.findActive().map(PolicyBundleVersion::revision).orElse(null);
    return new PolicySnapshot(config.version(), config.content(), revision);
  }

  public List<PolicyConfiguration> list() {
    return configurations.list();
  }

  public PolicyConfiguration get(String id) {
    return configurations.findById(id).orElseThrow(() -> new NotFoundException("Policy configuration " + id + " not found"));
  }

  public PolicyConfiguration createDraft(Map<String, Object> content, String comment, String actor) {
    validate(content);
    PolicyConfiguration created = configurations.insertDraft("cfg-" + UUID.randomUUID(), content, comment, actor, clock.instant());
    audit.record(actor, "POLICY_CONFIGURATION_CREATED", "policy_configuration", created.id(), Map.of("version", created.version(), "comment", comment == null ? "" : comment));
    return created;
  }

  public PolicyConfiguration activate(String id, String actor) {
    PolicyConfiguration target = get(id);
    if (target.status() == VersionStatus.ACTIVE) {
      return target;
    }
    validate(target.content());
    configurations.activate(id, clock.instant());
    audit.record(actor, "POLICY_CONFIGURATION_ACTIVATED", "policy_configuration", id, Map.of("version", target.version()));
    return get(id);
  }

  /** Re-activates the most recently retired version. */
  public PolicyConfiguration rollback(String actor) {
    PolicyConfiguration previous = configurations.list().stream()
        .filter(c -> c.status() == VersionStatus.RETIRED && c.retiredAt() != null)
        .max(Comparator.comparing(PolicyConfiguration::retiredAt))
        .orElseThrow(() -> new IllegalStateException("No retired policy configuration to roll back to"));
    configurations.activate(previous.id(), clock.instant());
    audit.record(actor, "POLICY_CONFIGURATION_ROLLED_BACK", "policy_configuration", previous.id(), Map.of("version", previous.version()));
    return get(previous.id());
  }

  static void validate(Map<String, Object> content) {
    List<String> missing = new ArrayList<>();
    for (String s : REQUIRED_SECTIONS) {
      if (!(content.get(s) instanceof Map)) missing.add(s);
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Policy configuration is missing object sections: " + missing);
    }
    Object limits = content.get("limits");
    if (limits instanceof Map<?, ?> l) {
      for (String k : List.of("maxTopicsPerManifest", "maxPartitionsPerTopic", "maxPartitionsPerPlan", "maxRetentionMs")) {
        if (!(l.get(k) instanceof Number n) || n.longValue() <= 0) {
          throw new IllegalArgumentException("limits." + k + " must be a positive number");
        }
      }
    }
  }
}

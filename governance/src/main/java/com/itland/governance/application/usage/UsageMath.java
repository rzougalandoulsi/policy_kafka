package com.itland.governance.application.usage;

import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.usage.RepositoryUsage;
import com.itland.governance.domain.usage.UsageCounts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure arithmetic behind the usage projection (no I/O). Counts follow the quota keys of input contract 2.0:
 * topics, topicPartitions, identityPools, rbacBindings (schemas, service accounts, connectors and Flink statements stay 0
 * until the contracts carry them).
 */
public final class UsageMath {

  private UsageMath() {}

  /** Confirmed usage of one repository; partitions come from the last declared manifest (1 per topic when unknown). */
  public static UsageCounts countsOf(RepositoryUsage u) {
    if (u == null) return UsageCounts.ZERO;
    long partitions = u.topics().stream().mapToLong(u::partitionsOf).sum();
    return UsageCounts.of(u.topics().size(), partitions, u.identityPools().size(), u.rbacBindings().size());
  }

  /** What a manifest declares as desired state. */
  public static UsageCounts declared(Manifest m) {
    long partitions = m.resources().topics().stream().mapToLong(t -> partitionsOf(t.partitions())).sum();
    long bindings = m.resources().rbac().size() + m.resources().identityPools().stream().mapToLong(p -> p.rbac().size()).sum();
    return UsageCounts.of(m.resources().topics().size(), partitions, m.resources().identityPools().size(), bindings);
  }

  public static Map<String, Integer> declaredPartitions(Manifest m) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Manifest.TopicResource t : m.resources().topics()) out.put(t.name(), partitionsOf(t.partitions()));
    return out;
  }

  /** Stage REQUEST delta: declared desired state minus this repository's confirmed usage (other repositories untouched). */
  public static UsageCounts manifestDelta(Manifest manifest, RepositoryUsage currentOfThisRepository) {
    return declared(manifest).minus(countsOf(currentOfThisRepository));
  }

  /**
   * Stage ACTION_PLAN delta from the planned actions: create +1 topic and +N partitions, delete -1 and -N (N as last
   * declared, 1 when unknown), partition increase +(new - known); identity pools and bindings +/-1.
   */
  public static UsageCounts actionsDelta(List<ProposedPlan.PlannedActionView> actions, RepositoryUsage currentOfThisRepository) {
    long topics = 0, partitions = 0, pools = 0, bindings = 0;
    for (ProposedPlan.PlannedActionView a : actions) {
      Map<String, String> payload = a.payload() == null ? Map.of() : a.payload();
      switch (a.actionType()) {
        case "TOPIC_CREATE" -> {
          topics++;
          partitions += parse(payload.get("partitions"), 1);
        }
        case "TOPIC_DELETE" -> {
          topics--;
          partitions -= currentOfThisRepository == null ? 1 : currentOfThisRepository.partitionsOf(a.resourceName());
        }
        case "TOPIC_UPDATE" -> {
          if (payload.containsKey("partitions")) {
            int known = currentOfThisRepository == null ? 0 : currentOfThisRepository.partitionsOf(a.resourceName());
            partitions += Math.max(0, parse(payload.get("partitions"), known) - known);
          }
        }
        case "IDENTITY_POOL_CREATE" -> pools++;
        case "IDENTITY_POOL_DELETE" -> pools--;
        case "RBAC_ASSIGN" -> bindings++;
        case "RBAC_REVOKE" -> bindings--;
        default -> { }
      }
    }
    return UsageCounts.of(topics, partitions, pools, bindings);
  }

  static int partitionsOf(Integer declared) {
    return declared == null || declared < 1 ? 1 : declared;
  }

  private static int parse(String value, int fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return Math.max(0, Integer.parseInt(value.trim()));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}

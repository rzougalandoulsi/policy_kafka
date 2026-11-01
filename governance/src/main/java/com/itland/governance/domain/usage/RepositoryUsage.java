package com.itland.governance.domain.usage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Confirmed resources of one repository in one environment as consumed from {@code teams.repo.manifest}
 * ({@code RepoConfirmed}: topic names, identity pool names, RBAC binding keys) plus the partitions Governance last saw
 * declared for those topics (the confirmed record carries names only). {@code stateVersion == -1} means no confirmed
 * snapshot has been consumed yet.
 */
public record RepositoryUsage(
    String teamId,
    String repositoryId,
    String environment,
    long stateVersion,
    List<String> topics,
    List<String> identityPools,
    List<String> rbacBindings,
    Map<String, Integer> declaredPartitions,
    Instant snapshotAt,
    Instant updatedAt) {

  public static final long NO_SNAPSHOT = -1;

  public RepositoryUsage {
    topics = topics == null ? List.of() : List.copyOf(topics);
    identityPools = identityPools == null ? List.of() : List.copyOf(identityPools);
    rbacBindings = rbacBindings == null ? List.of() : List.copyOf(rbacBindings);
    declaredPartitions = declaredPartitions == null ? Map.of() : Map.copyOf(declaredPartitions);
  }

  public boolean hasSnapshot() {
    return stateVersion > NO_SNAPSHOT;
  }

  /** Partitions of a confirmed topic as last declared to Governance; 1 (the Kafka minimum) when unknown. */
  public int partitionsOf(String topic) {
    Integer p = declaredPartitions.get(topic);
    return p == null || p < 1 ? 1 : p;
  }
}

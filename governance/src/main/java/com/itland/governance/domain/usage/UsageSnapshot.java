package com.itland.governance.domain.usage;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Everything OPA needs under {@code input.usage}, {@code input.ownership} and {@code input.repository}, projected by
 * Governance for one evaluation: the team's confirmed usage in the environment, reservations of other in-flight plans,
 * the delta of this request, per-repository breakdown, snapshot versions and the ownership conflicts of the resources
 * this request names.
 */
public record UsageSnapshot(
    UsageCounts current,
    UsageCounts reserved,
    UsageCounts delta,
    Map<String, UsageCounts> byRepository,
    Map<String, Long> asOfStateVersions,
    Duration snapshotAge,
    /** "TYPE:name" -> owner (repositoryId, or teamId/repositoryId for another team) for conflicting resources only. */
    Map<String, String> ownershipConflicts,
    boolean repositoryOwnedByTeam,
    /** State version of the usage snapshot for the request's own repository; empty when none was consumed yet. */
    OptionalLong repositoryStateVersion) {

  public static UsageSnapshot empty() {
    return new UsageSnapshot(UsageCounts.ZERO, UsageCounts.ZERO, UsageCounts.ZERO, Map.of(), Map.of(), null, Map.of(), true, OptionalLong.empty());
  }

  /**
   * The snapshot is stale for a plan when the Orchestrator planned against a confirmed state version Governance has not
   * consumed yet (quotas would be computed on outdated usage). A plan against version 0 never needs a snapshot.
   */
  public boolean staleFor(long planStateVersion) {
    if (planStateVersion <= 0) return false;
    return repositoryStateVersion.isEmpty() || repositoryStateVersion.getAsLong() < planStateVersion;
  }
}

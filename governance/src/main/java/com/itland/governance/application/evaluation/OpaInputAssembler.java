package com.itland.governance.application.evaluation;

import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.exception.PolicyException;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.policy.PolicySnapshot;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.team.TeamConfiguration;
import com.itland.governance.domain.usage.UsageCounts;
import com.itland.governance.domain.usage.UsageSnapshot;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the trusted OPA input (contract 2.0, see contracts/opa/input.schema.json). Principal, team configuration,
 * usage, ownership, exceptions and configuration always come from Governance's own sources (JWT, database, Kafka
 * confirmed state); nothing privileged is ever copied from the client request body. {@code now} is supplied so
 * time-based rules are deterministic and testable.
 */
public final class OpaInputAssembler {

  private OpaInputAssembler() {}

  public static Map<String, Object> build(GovernanceStage stage, String principalId, Set<String> roles, Manifest manifest,
                                          String requestId, String manifestDigest, ProposedPlan plan,
                                          PolicySnapshot snapshot, List<PolicyException> exceptions,
                                          TeamConfiguration team, UsageSnapshot usage, Instant now, String contractVersion) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("contractVersion", contractVersion);
    input.put("stage", stage.name());
    input.put("now", now.toString());
    input.put("principal", Map.of("id", principalId, "roles", List.copyOf(roles)));
    input.put("team", team(team));
    input.put("repository", repository(manifest.repositoryId(), usage));
    input.put("environment", manifest.environment());
    input.put("manifest", manifest(manifest));
    input.put("actions", plan == null ? List.of() : plan.actions().stream().map(OpaInputAssembler::action).toList());
    input.put("plan", plan == null ? Map.of() : Map.of("planId", plan.planId(), "planDigest", plan.planDigest(), "stateVersion", plan.stateVersion()));
    input.put("usage", usage(usage));
    input.put("ownership", Map.of("resources", new TreeMap<>(usage.ownershipConflicts())));
    input.put("request", Map.of("requestId", requestId, "manifestDigest", manifestDigest));
    input.put("configuration", snapshot.configuration());
    input.put("exceptions", exceptions.stream().map(OpaInputAssembler::exception).toList());
    return input;
  }

  static Map<String, Object> team(TeamConfiguration t) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", t.teamId());
    out.put("authorizedPrefixes", t.authorizedPrefixes());
    out.put("quotas", new TreeMap<>(t.quotas()));
    out.put("allowedRoles", t.allowedRoles());
    out.put("environments", t.environments());
    out.put("configurationVersion", t.version());
    out.put("source", t.source());
    return out;
  }

  static Map<String, Object> repository(String repositoryId, UsageSnapshot usage) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", repositoryId);
    out.put("ownedByTeam", usage.repositoryOwnedByTeam());
    out.put("stateVersion", usage.repositoryStateVersion().isPresent() ? usage.repositoryStateVersion().getAsLong() : null);
    return out;
  }

  static Map<String, Object> usage(UsageSnapshot u) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("current", u.current().sorted());
    out.put("reserved", u.reserved().sorted());
    out.put("delta", u.delta().sorted());
    Map<String, Object> byRepo = new TreeMap<>();
    u.byRepository().forEach((repo, counts) -> byRepo.put(repo, counts.sorted()));
    out.put("byRepository", byRepo);
    out.put("asOfStateVersions", new TreeMap<>(u.asOfStateVersions()));
    if (u.snapshotAge() != null) out.put("snapshotAge", u.snapshotAge().toString());
    return out;
  }

  static Map<String, Object> manifest(Manifest m) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("teamId", m.teamId());
    out.put("repositoryId", m.repositoryId());
    out.put("environment", m.environment());
    out.put("topics", m.resources().topics().stream().map(t -> {
      Map<String, Object> topic = new LinkedHashMap<>();
      topic.put("name", t.name());
      topic.put("partitions", t.partitions());
      topic.put("retentionMs", t.retentionMs());
      topic.put("config", t.config());
      return topic;
    }).toList());
    out.put("rbac", m.resources().rbac().stream().map(OpaInputAssembler::rbac).toList());
    out.put("identityPools", m.resources().identityPools().stream().map(p -> Map.of(
        "name", p.name(), "principals", p.principals(), "rbac", p.rbac().stream().map(OpaInputAssembler::rbac).toList())).toList());
    // Resource types the contracts do not carry yet (plan phase 6): present and empty so policies can already reference them.
    out.put("schemas", List.of());
    out.put("serviceAccounts", List.of());
    out.put("connectors", List.of());
    out.put("flinkStatements", List.of());
    return out;
  }

  private static Map<String, Object> rbac(Manifest.RbacResource r) {
    return Map.of("principal", r.principal(), "role", r.role(), "resource", r.resource());
  }

  private static Map<String, Object> action(ProposedPlan.PlannedActionView a) {
    return Map.of("actionId", a.actionId(), "resourceType", a.resourceType(), "resourceName", a.resourceName(),
        "actionType", a.actionType(), "payload", a.payload() == null ? Map.of() : a.payload());
  }

  private static Map<String, Object> exception(PolicyException e) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", e.id());
    out.put("policyId", e.policyId());
    out.put("principalId", e.principalId() == null ? "*" : e.principalId());
    out.put("teamId", e.teamId());
    out.put("environment", e.environment());
    out.put("resourcePattern", e.resourcePattern());
    out.put("actionTypes", e.actionTypes());
    return out;
  }

  /** Convenience for tests and diagnostics. */
  static UsageCounts zero() {
    return UsageCounts.ZERO;
  }
}

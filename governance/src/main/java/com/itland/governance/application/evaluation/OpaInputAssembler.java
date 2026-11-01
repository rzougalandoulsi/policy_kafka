package com.itland.governance.application.evaluation;

import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.exception.PolicyException;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.policy.PolicySnapshot;
import com.itland.governance.domain.request.ProposedPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the trusted OPA input. Principal, exceptions and configuration always come from Governance's own
 * sources (JWT, database); nothing privileged is ever copied from the client request body.
 */
public final class OpaInputAssembler {

  private OpaInputAssembler() {}

  public static Map<String, Object> build(GovernanceStage stage, String principalId, Set<String> roles, Manifest manifest,
                                          String requestId, String manifestDigest, ProposedPlan plan,
                                          PolicySnapshot snapshot, List<PolicyException> exceptions) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("stage", stage.name());
    input.put("principal", Map.of("id", principalId, "roles", List.copyOf(roles)));
    input.put("team", Map.of("id", manifest.teamId()));
    input.put("environment", manifest.environment());
    input.put("manifest", manifest(manifest));
    input.put("actions", plan == null ? List.of() : plan.actions().stream().map(OpaInputAssembler::action).toList());
    input.put("plan", plan == null ? Map.of() : Map.of("planId", plan.planId(), "planDigest", plan.planDigest(), "stateVersion", plan.stateVersion()));
    input.put("request", Map.of("requestId", requestId, "manifestDigest", manifestDigest));
    input.put("configuration", snapshot.configuration());
    input.put("exceptions", exceptions.stream().map(OpaInputAssembler::exception).toList());
    return input;
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
}

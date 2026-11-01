package com.itland.governance.infrastructure.persistence;

import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.PolicyViolation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Findings of policies in shadow mode, persisted next to the decision that produced them. */
@Repository
public class ShadowFindingRepository {

  public record ShadowFinding(long id, String decisionId, String requestId, String planId, String stage, String kind, String policyId,
                              String severity, String message, String resourceType, String resourceName, Instant recordedAt,
                              String teamId, String repositoryId, String environment, String principalId) {}

  private final JdbcClient jdbc;

  public ShadowFindingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts one row per shadow violation and shadow approval requirement of the decision (caller's transaction). */
  public int insertFor(GovernanceDecision d) {
    int n = 0;
    for (PolicyViolation v : d.shadowViolations()) {
      insert(d, "VIOLATION", v.policyId(), v.severity(), v.message(), v.resourceType(), v.resourceName());
      n++;
    }
    for (ApprovalRequirement a : d.shadowApprovals()) {
      insert(d, "APPROVAL", a.policyId(), "REQUIRE_APPROVAL", a.message(), a.resourceType(), a.resourceName());
      n++;
    }
    return n;
  }

  private void insert(GovernanceDecision d, String kind, String policyId, String severity, String message, String resourceType, String resourceName) {
    jdbc.sql("""
        INSERT INTO shadow_finding (decision_id, request_id, plan_id, stage, kind, policy_id, severity, message, resource_type, resource_name, recorded_at)
        VALUES (:d, :r, :p, :stage, :kind, :policy, :severity, :message, :rtype, :rname, :at)
        """)
        .param("d", d.id()).param("r", d.requestId()).param("p", d.planId()).param("stage", d.stage().name()).param("kind", kind)
        .param("policy", policyId).param("severity", severity).param("message", message).param("rtype", resourceType).param("rname", resourceName)
        .param("at", Jdbc.ts(d.evaluatedAt())).update();
  }

  public List<ShadowFinding> query(String policyId, String teamId, String environment, Instant since, int limit) {
    return jdbc.sql("""
        SELECT f.*, r.team_id, r.repository_id, r.environment, r.principal_id FROM shadow_finding f JOIN manifest_request r ON r.id = f.request_id
        WHERE (cast(:policy as text) IS NULL OR f.policy_id = :policy) AND (cast(:team as text) IS NULL OR r.team_id = :team)
          AND (cast(:env as text) IS NULL OR r.environment = :env) AND (cast(:since as timestamptz) IS NULL OR f.recorded_at >= :since)
        ORDER BY f.recorded_at DESC, f.id DESC LIMIT :limit
        """)
        .param("policy", policyId).param("team", teamId).param("env", environment).param("since", Jdbc.ts(since)).param("limit", limit)
        .query((rs, i) -> new ShadowFinding(rs.getLong("id"), rs.getString("decision_id"), rs.getString("request_id"), rs.getString("plan_id"),
            rs.getString("stage"), rs.getString("kind"), rs.getString("policy_id"), rs.getString("severity"), rs.getString("message"),
            rs.getString("resource_type"), rs.getString("resource_name"), Jdbc.instant(rs, "recorded_at"), rs.getString("team_id"),
            rs.getString("repository_id"), rs.getString("environment"), rs.getString("principal_id")))
        .list();
  }

  public long count(Instant since) {
    return jdbc.sql("SELECT count(*) FROM shadow_finding WHERE recorded_at >= :since").param("since", Jdbc.ts(since)).query(Long.class).single();
  }
}

package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.decision.PolicyViolation;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Optional;

@Repository
public class DecisionRepository {

  private static final TypeReference<List<PolicyViolation>> VIOLATIONS = new TypeReference<>() {};
  private static final TypeReference<List<ApprovalRequirement>> APPROVALS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public DecisionRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<GovernanceDecision> mapper = (rs, i) -> new GovernanceDecision(
      rs.getString("id"), rs.getString("request_id"), rs.getString("plan_id"),
      GovernanceStage.valueOf(rs.getString("stage")), DecisionType.valueOf(rs.getString("decision_type")),
      rs.getString("policy_revision"), rs.getLong("configuration_version"), rs.getString("digest"),
      json.readStrings(rs.getString("matched_policies")), json.read(rs.getString("violations"), VIOLATIONS),
      json.read(rs.getString("approval_requirements"), APPROVALS), json.readStrings(rs.getString("applied_exceptions")),
      rs.getString("error_message"), Jdbc.longOrNull(rs, "opa_latency_ms"), Jdbc.instant(rs, "evaluated_at"));

  public void insert(GovernanceDecision d) {
    jdbc.sql("""
        INSERT INTO governance_decision (id, request_id, plan_id, stage, decision_type, policy_revision, configuration_version,
          digest, matched_policies, violations, approval_requirements, applied_exceptions, error_message, opa_latency_ms, evaluated_at)
        VALUES (:id, :request, :plan, :stage, :type, :rev, :cfg, :digest, cast(:matched as jsonb), cast(:violations as jsonb),
          cast(:approvals as jsonb), cast(:exceptions as jsonb), :error, :latency, :at)
        """)
        .param("id", d.id()).param("request", d.requestId()).param("plan", d.planId()).param("stage", d.stage().name())
        .param("type", d.type().name()).param("rev", d.policyRevision()).param("cfg", d.configurationVersion())
        .param("digest", d.digest()).param("matched", json.write(d.matchedPolicies())).param("violations", json.write(d.violations()))
        .param("approvals", json.write(d.approvalRequirements())).param("exceptions", json.write(d.appliedExceptions()))
        .param("error", d.errorMessage()).param("latency", d.opaLatencyMs()).param("at", Jdbc.ts(d.evaluatedAt()))
        .update();
  }

  public Optional<GovernanceDecision> findById(String id) {
    return jdbc.sql("SELECT * FROM governance_decision WHERE id = :id").param("id", id).query(mapper).optional();
  }

  public List<GovernanceDecision> findByRequest(String requestId) {
    return jdbc.sql("SELECT * FROM governance_decision WHERE request_id = :r ORDER BY evaluated_at, id")
        .param("r", requestId).query(mapper).list();
  }
}

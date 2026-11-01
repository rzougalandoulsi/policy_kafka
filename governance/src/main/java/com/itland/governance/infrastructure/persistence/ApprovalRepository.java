package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.approval.ApprovalDecision;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.approval.ApprovalVerdict;
import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.policy.ApprovalConfiguration;
import com.itland.governance.domain.policy.VersionStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ApprovalRepository {

  private static final TypeReference<List<ApprovalRequirement>> REQUIREMENTS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public ApprovalRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private ApprovalRequest mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new ApprovalRequest(
      rs.getString("id"), rs.getString("request_id"), GovernanceStage.valueOf(rs.getString("stage")), rs.getString("decision_id"),
      rs.getString("plan_id"), rs.getString("plan_digest"), rs.getString("digest"), ApprovalStatus.valueOf(rs.getString("status")),
      rs.getString("requester_id"), rs.getInt("required_approvals"), json.readStrings(rs.getString("approver_roles")),
      rs.getBoolean("separation_of_duties"), json.read(rs.getString("requirements"), REQUIREMENTS), Jdbc.instant(rs, "expires_at"),
      rs.getLong("version"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "resolved_at"), rs.getString("resolution_reason"));
  }

  private final RowMapper<ApprovalRequest> mapper = this::mapRow;

  private final RowMapper<ApprovalDecision> decisionMapper = (rs, i) -> new ApprovalDecision(
      rs.getString("id"), rs.getString("approval_id"), rs.getString("approver_id"), ApprovalVerdict.valueOf(rs.getString("verdict")),
      rs.getString("comment"), Jdbc.instant(rs, "decided_at"));

  public void insert(ApprovalRequest a) {
    jdbc.sql("""
        INSERT INTO approval_request (id, request_id, stage, decision_id, plan_id, plan_digest, digest, status, requester_id, required_approvals,
          approver_roles, separation_of_duties, requirements, expires_at, version, created_at)
        VALUES (:id, :request, :stage, :decision, :plan, :planDigest, :digest, :status, :requester, :required, cast(:roles as jsonb), :sod,
          cast(:requirements as jsonb), :expires, 0, :now)
        """)
        .param("id", a.id()).param("request", a.requestId()).param("stage", a.stage().name()).param("decision", a.decisionId())
        .param("plan", a.planId()).param("planDigest", a.planDigest()).param("digest", a.digest()).param("status", a.status().name())
        .param("requester", a.requesterId()).param("required", a.requiredApprovals()).param("roles", json.write(a.approverRoles()))
        .param("sod", a.separationOfDuties()).param("requirements", json.write(a.requirements())).param("expires", Jdbc.ts(a.expiresAt()))
        .param("now", Jdbc.ts(a.createdAt()))
        .update();
  }

  public Optional<ApprovalRequest> findById(String id) {
    return jdbc.sql("SELECT * FROM approval_request WHERE id = :id").param("id", id).query(mapper).optional();
  }

  /** Row lock for concurrent decision protection; must run inside a transaction. */
  public Optional<ApprovalRequest> lockById(String id) {
    return jdbc.sql("SELECT * FROM approval_request WHERE id = :id FOR UPDATE").param("id", id).query(mapper).optional();
  }

  public List<ApprovalRequest> list(ApprovalStatus status, String teamId, int limit) {
    return jdbc.sql("""
        SELECT a.* FROM approval_request a JOIN manifest_request r ON r.id = a.request_id
        WHERE (cast(:status as text) IS NULL OR a.status = :status) AND (cast(:team as text) IS NULL OR r.team_id = :team)
        ORDER BY a.created_at DESC LIMIT :limit
        """)
        .param("status", status == null ? null : status.name()).param("team", teamId).param("limit", limit).query(mapper).list();
  }

  public List<ApprovalRequest> findPendingByRequest(String requestId) {
    return jdbc.sql("SELECT * FROM approval_request WHERE request_id = :r AND status = 'PENDING'").param("r", requestId).query(mapper).list();
  }

  public List<ApprovalRequest> findExpired(Instant now, int limit) {
    return jdbc.sql("SELECT * FROM approval_request WHERE status = 'PENDING' AND expires_at <= :now ORDER BY expires_at LIMIT :l")
        .param("now", Jdbc.ts(now)).param("l", limit).query(mapper).list();
  }

  public boolean resolve(String id, long expectedVersion, ApprovalStatus status, String reason, Instant now) {
    return jdbc.sql("UPDATE approval_request SET status = :s, resolved_at = :now, resolution_reason = :reason, version = version + 1 WHERE id = :id AND version = :v")
        .param("s", status.name()).param("now", Jdbc.ts(now)).param("reason", reason).param("id", id).param("v", expectedVersion).update() == 1;
  }

  public long countPending() {
    return jdbc.sql("SELECT count(*) FROM approval_request WHERE status = 'PENDING'").query(Long.class).single();
  }

  /** Returns false when this approver already recorded a verdict (idempotent decisions). */
  public boolean insertDecision(ApprovalDecision d) {
    try {
      jdbc.sql("INSERT INTO approval_decision (id, approval_id, approver_id, verdict, comment, decided_at) VALUES (:id, :a, :by, :v, :c, :at)")
          .param("id", d.id()).param("a", d.approvalId()).param("by", d.approverId()).param("v", d.verdict().name())
          .param("c", d.comment()).param("at", Jdbc.ts(d.decidedAt())).update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  public List<ApprovalDecision> decisions(String approvalId) {
    return jdbc.sql("SELECT * FROM approval_decision WHERE approval_id = :a ORDER BY decided_at").param("a", approvalId).query(decisionMapper).list();
  }

  // ---- approval configuration ----

  private ApprovalConfiguration mapConfig(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return ApprovalConfiguration.fromContent(
      rs.getString("id"), rs.getLong("version"), json.readMap(rs.getString("content")), VersionStatus.valueOf(rs.getString("status")),
      rs.getString("created_by"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "activated_at"));
  }

  private final RowMapper<ApprovalConfiguration> configMapper = this::mapConfig;

  public Optional<ApprovalConfiguration> findActiveConfiguration() {
    return jdbc.sql("SELECT * FROM approval_configuration WHERE status = 'ACTIVE'").query(configMapper).optional();
  }

  public List<ApprovalConfiguration> listConfigurations() {
    return jdbc.sql("SELECT * FROM approval_configuration ORDER BY version DESC").query(configMapper).list();
  }

  public ApprovalConfiguration insertConfiguration(String id, Map<String, Object> content, String by, Instant now) {
    jdbc.sql("INSERT INTO approval_configuration (id, content, status, created_by, created_at) VALUES (:id, cast(:c as jsonb), 'DRAFT', :by, :now)")
        .param("id", id).param("c", json.write(content)).param("by", by).param("now", Jdbc.ts(now)).update();
    return jdbc.sql("SELECT * FROM approval_configuration WHERE id = :id").param("id", id).query(configMapper).single();
  }

  public void activateConfiguration(String id, Instant now) {
    jdbc.sql("UPDATE approval_configuration SET status = 'RETIRED' WHERE status = 'ACTIVE' AND id <> :id").param("id", id).update();
    int n = jdbc.sql("UPDATE approval_configuration SET status = 'ACTIVE', activated_at = :now WHERE id = :id").param("now", Jdbc.ts(now)).param("id", id).update();
    if (n != 1) throw new IllegalArgumentException("Unknown approval configuration " + id);
  }
}

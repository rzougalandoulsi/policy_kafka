package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.exception.PolicyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PolicyExceptionRepository {

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public PolicyExceptionRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<PolicyException> mapper = (rs, i) -> new PolicyException(
      rs.getString("id"), rs.getString("policy_id"), rs.getString("principal_id"), rs.getString("team_id"), rs.getString("environment"),
      rs.getString("resource_pattern"), json.readStrings(rs.getString("action_types")), rs.getString("reason"), Jdbc.instant(rs, "expires_at"),
      rs.getString("created_by"), Jdbc.instant(rs, "created_at"), rs.getString("revoked_by"), Jdbc.instant(rs, "revoked_at"), rs.getString("revoke_reason"));

  public void insert(PolicyException e) {
    jdbc.sql("""
        INSERT INTO policy_exception (id, policy_id, principal_id, team_id, environment, resource_pattern, action_types, reason, expires_at, created_by, created_at)
        VALUES (:id, :policy, :principal, :team, :env, :pattern, cast(:actions as jsonb), :reason, :expires, :by, :now)
        """)
        .param("id", e.id()).param("policy", e.policyId()).param("principal", e.principalId()).param("team", e.teamId()).param("env", e.environment())
        .param("pattern", e.resourcePattern()).param("actions", json.write(e.actionTypes())).param("reason", e.reason())
        .param("expires", Jdbc.ts(e.expiresAt())).param("by", e.createdBy()).param("now", Jdbc.ts(e.createdAt()))
        .update();
  }

  public Optional<PolicyException> findById(String id) {
    return jdbc.sql("SELECT * FROM policy_exception WHERE id = :id").param("id", id).query(mapper).optional();
  }

  /** Candidate exceptions for a caller/team/environment; final scope matching (resource, action) happens in OPA. */
  public List<PolicyException> findActive(String teamId, String environment, String principalId, Instant now) {
    return jdbc.sql("""
        SELECT * FROM policy_exception
        WHERE team_id = :team AND (environment = :env OR environment = '*')
          AND (principal_id IS NULL OR principal_id = :principal)
          AND revoked_at IS NULL AND expires_at > :now
        ORDER BY created_at
        """)
        .param("team", teamId).param("env", environment).param("principal", principalId).param("now", Jdbc.ts(now))
        .query(mapper).list();
  }

  public List<PolicyException> list(String teamId, boolean includeInactive, Instant now) {
    return jdbc.sql("""
        SELECT * FROM policy_exception
        WHERE (cast(:team as text) IS NULL OR team_id = :team)
          AND (cast(:all as boolean) OR (revoked_at IS NULL AND expires_at > :now))
        ORDER BY created_at DESC
        """)
        .param("team", teamId).param("all", includeInactive).param("now", Jdbc.ts(now)).query(mapper).list();
  }

  public boolean revoke(String id, String by, String reason, Instant now) {
    return jdbc.sql("UPDATE policy_exception SET revoked_by = :by, revoked_at = :now, revoke_reason = :reason WHERE id = :id AND revoked_at IS NULL")
        .param("by", by).param("now", Jdbc.ts(now)).param("reason", reason).param("id", id).update() == 1;
  }
}

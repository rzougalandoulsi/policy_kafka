package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.policy.PolicyConfiguration;
import com.itland.governance.domain.policy.VersionStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PolicyConfigurationRepository {

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public PolicyConfigurationRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<PolicyConfiguration> mapper = (rs, i) -> new PolicyConfiguration(
      rs.getString("id"), rs.getLong("version"), json.readMap(rs.getString("content")), VersionStatus.valueOf(rs.getString("status")),
      rs.getString("comment"), rs.getString("created_by"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "activated_at"), Jdbc.instant(rs, "retired_at"));

  public PolicyConfiguration insertDraft(String id, Map<String, Object> content, String comment, String createdBy, Instant now) {
    jdbc.sql("INSERT INTO policy_configuration (id, content, status, comment, created_by, created_at) VALUES (:id, cast(:c as jsonb), 'DRAFT', :comment, :by, :now)")
        .param("id", id).param("c", json.write(content)).param("comment", comment).param("by", createdBy).param("now", Jdbc.ts(now)).update();
    return findById(id).orElseThrow();
  }

  public Optional<PolicyConfiguration> findById(String id) {
    return jdbc.sql("SELECT * FROM policy_configuration WHERE id = :id").param("id", id).query(mapper).optional();
  }

  public Optional<PolicyConfiguration> findActive() {
    return jdbc.sql("SELECT * FROM policy_configuration WHERE status = 'ACTIVE'").query(mapper).optional();
  }

  public List<PolicyConfiguration> list() {
    return jdbc.sql("SELECT * FROM policy_configuration ORDER BY version DESC").query(mapper).list();
  }

  /** Atomically retires the current ACTIVE row and activates the given one (within the caller's transaction). */
  public void activate(String id, Instant now) {
    jdbc.sql("UPDATE policy_configuration SET status = 'RETIRED', retired_at = :now WHERE status = 'ACTIVE' AND id <> :id")
        .param("now", Jdbc.ts(now)).param("id", id).update();
    int n = jdbc.sql("UPDATE policy_configuration SET status = 'ACTIVE', activated_at = :now, retired_at = NULL WHERE id = :id")
        .param("now", Jdbc.ts(now)).param("id", id).update();
    if (n != 1) throw new IllegalArgumentException("Unknown policy configuration " + id);
  }
}

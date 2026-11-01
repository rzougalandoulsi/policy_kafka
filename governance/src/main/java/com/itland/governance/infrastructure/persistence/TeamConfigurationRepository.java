package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.team.TeamConfiguration;
import com.itland.governance.domain.team.TeamConfigurationChange;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TeamConfigurationRepository {

  private static final TypeReference<Map<String, Long>> QUOTAS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public TeamConfigurationRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<TeamConfiguration> mapper = (rs, i) -> new TeamConfiguration(
      rs.getString("team_id"), rs.getLong("version"), json.readStrings(rs.getString("authorized_prefixes")),
      json.read(rs.getString("quotas"), QUOTAS), json.readStrings(rs.getString("allowed_roles")), json.readMap(rs.getString("environments")),
      rs.getString("source"), rs.getString("source_version"), Jdbc.instant(rs, "synced_at"), rs.getString("comment"), rs.getString("updated_by"),
      Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));

  private final RowMapper<TeamConfigurationChange> changeMapper = (rs, i) -> new TeamConfigurationChange(
      rs.getLong("id"), rs.getString("team_id"), rs.getLong("version"), json.readMap(rs.getString("content")), rs.getString("action"),
      rs.getString("changed_by"), Jdbc.instant(rs, "changed_at"), rs.getString("comment"));

  public Optional<TeamConfiguration> findById(String teamId) {
    return jdbc.sql("SELECT * FROM team_configuration WHERE team_id = :t").param("t", teamId).query(mapper).optional();
  }

  /**
   * Row lock serialising quota admission per team; must run inside a transaction. Empty when the team has no stored
   * configuration (and therefore no quotas to protect).
   */
  public Optional<TeamConfiguration> lockById(String teamId) {
    return jdbc.sql("SELECT * FROM team_configuration WHERE team_id = :t FOR UPDATE").param("t", teamId).query(mapper).optional();
  }

  public List<TeamConfiguration> list() {
    return jdbc.sql("SELECT * FROM team_configuration ORDER BY team_id").query(mapper).list();
  }

  /** Inserts or bumps the version; returns the stored row. */
  public TeamConfiguration upsert(TeamConfiguration c, Instant now) {
    jdbc.sql("""
        INSERT INTO team_configuration (team_id, version, authorized_prefixes, quotas, allowed_roles, environments, source, source_version, synced_at,
          comment, updated_by, created_at, updated_at)
        VALUES (:t, 1, cast(:prefixes as jsonb), cast(:quotas as jsonb), cast(:roles as jsonb), cast(:envs as jsonb), :source, :sourceVersion, :synced,
          :comment, :by, :now, :now)
        ON CONFLICT (team_id) DO UPDATE SET version = team_configuration.version + 1,
          authorized_prefixes = EXCLUDED.authorized_prefixes, quotas = EXCLUDED.quotas, allowed_roles = EXCLUDED.allowed_roles,
          environments = EXCLUDED.environments, source = EXCLUDED.source, source_version = EXCLUDED.source_version, synced_at = EXCLUDED.synced_at,
          comment = EXCLUDED.comment, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
        """)
        .param("t", c.teamId()).param("prefixes", json.write(c.authorizedPrefixes())).param("quotas", json.write(c.quotas()))
        .param("roles", json.write(c.allowedRoles())).param("envs", json.write(c.environments())).param("source", c.source())
        .param("sourceVersion", c.sourceVersion()).param("synced", Jdbc.ts(c.syncedAt())).param("comment", c.comment())
        .param("by", c.updatedBy()).param("now", Jdbc.ts(now)).update();
    return findById(c.teamId()).orElseThrow();
  }

  public boolean delete(String teamId) {
    return jdbc.sql("DELETE FROM team_configuration WHERE team_id = :t").param("t", teamId).update() == 1;
  }

  public void recordChange(String teamId, long version, Map<String, Object> content, String action, String by, Instant at, String comment) {
    jdbc.sql("INSERT INTO team_configuration_history (team_id, version, content, action, changed_by, changed_at, comment) VALUES (:t, :v, cast(:c as jsonb), :a, :by, :at, :comment)")
        .param("t", teamId).param("v", version).param("c", json.write(content)).param("a", action).param("by", by).param("at", Jdbc.ts(at)).param("comment", comment).update();
  }

  public List<TeamConfigurationChange> history(String teamId, int limit) {
    return jdbc.sql("SELECT * FROM team_configuration_history WHERE team_id = :t ORDER BY id DESC LIMIT :l")
        .param("t", teamId).param("l", limit).query(changeMapper).list();
  }
}

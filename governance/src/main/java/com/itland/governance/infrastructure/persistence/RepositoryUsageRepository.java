package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.usage.RepositoryUsage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Confirmed resources per (team, repository, environment) consumed from teams.repo.manifest. */
@Repository
public class RepositoryUsageRepository {

  private static final TypeReference<Map<String, Integer>> PARTITIONS = new TypeReference<>() {};
  private static final TypeReference<Map<String, List<String>>> RESOURCES = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public RepositoryUsageRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<RepositoryUsage> mapper = (rs, i) -> {
    Map<String, List<String>> res = json.read(rs.getString("resources"), RESOURCES);
    return new RepositoryUsage(rs.getString("team_id"), rs.getString("repository_id"), rs.getString("environment"), rs.getLong("state_version"),
        res.getOrDefault("topics", List.of()), res.getOrDefault("identityPools", List.of()), res.getOrDefault("rbacBindings", List.of()),
        json.read(rs.getString("declared_partitions"), PARTITIONS), Jdbc.instant(rs, "snapshot_at"), Jdbc.instant(rs, "updated_at"));
  };

  /** Applies a confirmed snapshot when it is at least as new as the stored one (compacted topic replays are harmless). Returns true when applied. */
  public boolean upsertConfirmed(String teamId, String repositoryId, String environment, long stateVersion,
                                 List<String> topics, List<String> identityPools, List<String> rbacBindings, Instant now) {
    Map<String, Object> resources = new LinkedHashMap<>();
    resources.put("topics", topics);
    resources.put("identityPools", identityPools);
    resources.put("rbacBindings", rbacBindings);
    return jdbc.sql("""
        INSERT INTO repository_usage (team_id, repository_id, environment, state_version, resources, snapshot_at, updated_at)
        VALUES (:t, :r, :e, :v, cast(:res as jsonb), :now, :now)
        ON CONFLICT (team_id, repository_id, environment) DO UPDATE
          SET state_version = EXCLUDED.state_version, resources = EXCLUDED.resources, snapshot_at = :now, updated_at = :now
          WHERE repository_usage.state_version <= EXCLUDED.state_version
        """)
        .param("t", teamId).param("r", repositoryId).param("e", environment).param("v", stateVersion)
        .param("res", json.write(resources)).param("now", Jdbc.ts(now)).update() == 1;
  }

  /** Remembers the partitions a manifest declared per topic (merged; RepoConfirmed carries names only). */
  public void recordDeclaredPartitions(String teamId, String repositoryId, String environment, Map<String, Integer> partitions, Instant now) {
    jdbc.sql("""
        INSERT INTO repository_usage (team_id, repository_id, environment, declared_partitions, updated_at)
        VALUES (:t, :r, :e, cast(:p as jsonb), :now)
        ON CONFLICT (team_id, repository_id, environment) DO UPDATE
          SET declared_partitions = repository_usage.declared_partitions || EXCLUDED.declared_partitions, updated_at = :now
        """)
        .param("t", teamId).param("r", repositoryId).param("e", environment).param("p", json.write(partitions)).param("now", Jdbc.ts(now)).update();
  }

  public Optional<RepositoryUsage> find(String teamId, String repositoryId, String environment) {
    return jdbc.sql("SELECT * FROM repository_usage WHERE team_id = :t AND repository_id = :r AND environment = :e")
        .param("t", teamId).param("r", repositoryId).param("e", environment).query(mapper).optional();
  }

  public List<RepositoryUsage> findByTeam(String teamId, String environment) {
    return jdbc.sql("SELECT * FROM repository_usage WHERE team_id = :t AND environment = :e ORDER BY repository_id")
        .param("t", teamId).param("e", environment).query(mapper).list();
  }

  public List<RepositoryUsage> findByTeam(String teamId) {
    return jdbc.sql("SELECT * FROM repository_usage WHERE team_id = :t ORDER BY environment, repository_id").param("t", teamId).query(mapper).list();
  }

  /** Repositories in the environment whose confirmed topics or identity pools include any of the given names. */
  public List<RepositoryUsage> findOwners(String environment, Collection<String> topicNames, Collection<String> poolNames) {
    if (topicNames.isEmpty() && poolNames.isEmpty()) return List.of();
    return jdbc.sql("""
        SELECT * FROM repository_usage
        WHERE environment = :e AND state_version >= 0
          AND (jsonb_exists_any(resources->'topics', (SELECT coalesce(array_agg(x), '{}') FROM jsonb_array_elements_text(cast(:topics as jsonb)) x))
            OR jsonb_exists_any(resources->'identityPools', (SELECT coalesce(array_agg(x), '{}') FROM jsonb_array_elements_text(cast(:pools as jsonb)) x)))
        ORDER BY team_id, repository_id
        """)
        .param("e", environment).param("topics", json.write(List.copyOf(topicNames))).param("pools", json.write(List.copyOf(poolNames)))
        .query(mapper).list();
  }

  /** True when confirmed state for this repository id exists under a different team in the environment. */
  public boolean ownedByAnotherTeam(String teamId, String repositoryId, String environment) {
    return jdbc.sql("SELECT count(*) FROM repository_usage WHERE repository_id = :r AND environment = :e AND team_id <> :t AND state_version >= 0")
        .param("r", repositoryId).param("e", environment).param("t", teamId).query(Long.class).single() > 0;
  }
}

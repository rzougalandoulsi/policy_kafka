package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.request.ProposedPlan;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ProposedPlanRepository {

  private static final TypeReference<List<ProposedPlan.PlannedActionView>> ACTIONS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public ProposedPlanRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<ProposedPlan> mapper = (rs, i) -> new ProposedPlan(
      rs.getString("plan_id"), rs.getString("request_id"), rs.getString("plan_digest"), rs.getString("manifest_digest"),
      rs.getLong("state_version"), json.read(rs.getString("actions"), ACTIONS), rs.getString("event_id"), Jdbc.instant(rs, "received_at"));

  public void upsert(ProposedPlan p) {
    jdbc.sql("""
        INSERT INTO proposed_plan (plan_id, request_id, plan_digest, manifest_digest, state_version, actions, event_id, received_at)
        VALUES (:plan, :request, :digest, :mdigest, :sv, cast(:actions as jsonb), :event, :at)
        ON CONFLICT (plan_id) DO NOTHING
        """)
        .param("plan", p.planId()).param("request", p.requestId()).param("digest", p.planDigest()).param("mdigest", p.manifestDigest())
        .param("sv", p.stateVersion()).param("actions", json.write(p.actions())).param("event", p.eventId()).param("at", Jdbc.ts(p.receivedAt()))
        .update();
  }

  public Optional<ProposedPlan> findByPlanId(String planId) {
    return jdbc.sql("SELECT * FROM proposed_plan WHERE plan_id = :p").param("p", planId).query(mapper).optional();
  }

  public Optional<ProposedPlan> findLatestByRequest(String requestId) {
    return jdbc.sql("SELECT * FROM proposed_plan WHERE request_id = :r ORDER BY received_at DESC LIMIT 1")
        .param("r", requestId).query(mapper).optional();
  }

  /** Latest confirmed-state version per (team, repository, environment), as reported by Orchestrator terminal events. */
  public Optional<Long> latestStateVersion(String teamId, String repositoryId, String environment) {
    return jdbc.sql("SELECT state_version FROM repository_state WHERE team_id = :t AND repository_id = :r AND environment = :e")
        .param("t", teamId).param("r", repositoryId).param("e", environment).query(Long.class).optional();
  }

  public void recordStateVersion(String teamId, String repositoryId, String environment, long stateVersion, Instant now) {
    jdbc.sql("""
        INSERT INTO repository_state (team_id, repository_id, environment, state_version, updated_at) VALUES (:t, :r, :e, :v, :now)
        ON CONFLICT (team_id, repository_id, environment) DO UPDATE SET state_version = GREATEST(repository_state.state_version, EXCLUDED.state_version), updated_at = :now
        """).param("t", teamId).param("r", repositoryId).param("e", environment).param("v", stateVersion).param("now", Jdbc.ts(now)).update();
  }
}

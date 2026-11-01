package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.usage.QuotaReservation;
import com.itland.governance.domain.usage.UsageCounts;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class QuotaReservationRepository {

  private static final TypeReference<Map<String, Long>> DELTAS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public QuotaReservationRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<QuotaReservation> mapper = (rs, i) -> new QuotaReservation(
      rs.getString("id"), rs.getString("team_id"), rs.getString("environment"), rs.getString("request_id"), rs.getString("plan_id"),
      rs.getString("decision_id"), new UsageCounts(json.read(rs.getString("deltas"), DELTAS)), Jdbc.instant(rs, "created_at"),
      Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "released_at"), rs.getString("release_reason"));

  public void insert(QuotaReservation r) {
    jdbc.sql("""
        INSERT INTO quota_reservation (id, team_id, environment, request_id, plan_id, decision_id, deltas, created_at, expires_at)
        VALUES (:id, :t, :e, :r, :p, :d, cast(:deltas as jsonb), :created, :expires)
        """)
        .param("id", r.id()).param("t", r.teamId()).param("e", r.environment()).param("r", r.requestId()).param("p", r.planId())
        .param("d", r.decisionId()).param("deltas", json.write(r.deltas().sorted())).param("created", Jdbc.ts(r.createdAt()))
        .param("expires", Jdbc.ts(r.expiresAt())).update();
  }

  /** Open (unreleased, unexpired) reservations of a team in an environment, optionally excluding one request's own. */
  public List<QuotaReservation> findOpen(String teamId, String environment, String excludingRequestId, Instant now) {
    return jdbc.sql("""
        SELECT * FROM quota_reservation
        WHERE team_id = :t AND environment = :e AND released_at IS NULL AND expires_at > :now
          AND (cast(:x as text) IS NULL OR request_id <> :x)
        ORDER BY created_at
        """)
        .param("t", teamId).param("e", environment).param("now", Jdbc.ts(now)).param("x", excludingRequestId).query(mapper).list();
  }

  public List<QuotaReservation> findOpenByTeam(String teamId, Instant now) {
    return jdbc.sql("SELECT * FROM quota_reservation WHERE team_id = :t AND released_at IS NULL AND expires_at > :now ORDER BY environment, created_at")
        .param("t", teamId).param("now", Jdbc.ts(now)).query(mapper).list();
  }

  public int releaseByRequest(String requestId, String reason, Instant now) {
    return jdbc.sql("UPDATE quota_reservation SET released_at = :now, release_reason = :reason WHERE request_id = :r AND released_at IS NULL")
        .param("now", Jdbc.ts(now)).param("reason", reason).param("r", requestId).update();
  }

  public int releaseExpired(Instant now) {
    return jdbc.sql("UPDATE quota_reservation SET released_at = :now, release_reason = 'EXPIRED' WHERE released_at IS NULL AND expires_at <= :now")
        .param("now", Jdbc.ts(now)).update();
  }

  public long countOpen(Instant now) {
    return jdbc.sql("SELECT count(*) FROM quota_reservation WHERE released_at IS NULL AND expires_at > :now").param("now", Jdbc.ts(now)).query(Long.class).single();
  }
}

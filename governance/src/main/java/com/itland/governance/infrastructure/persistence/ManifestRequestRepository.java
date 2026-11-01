package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.RequestStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ManifestRequestRepository {

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public ManifestRequestRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<ManifestRequest> mapper = (rs, i) -> new ManifestRequest(
      rs.getString("id"), rs.getString("principal_id"), rs.getString("idempotency_key"),
      rs.getString("team_id"), rs.getString("repository_id"), rs.getString("environment"),
      json.read(rs.getString("manifest"), Manifest.class), rs.getString("manifest_digest"),
      RequestStatus.valueOf(rs.getString("status")), rs.getString("plan_id"), rs.getString("plan_digest"),
      Jdbc.longOrNull(rs, "state_version"), rs.getString("failure_reason"), rs.getLong("version"),
      Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"), Jdbc.instant(rs, "completed_at"));

  public void insert(ManifestRequest r) {
    jdbc.sql("""
        INSERT INTO manifest_request (id, principal_id, idempotency_key, team_id, repository_id, environment, manifest,
          manifest_digest, status, version, created_at, updated_at)
        VALUES (:id, :principal, :key, :team, :repo, :env, cast(:manifest as jsonb), :digest, :status, 0, :created, :created)
        """)
        .param("id", r.id()).param("principal", r.principalId()).param("key", r.idempotencyKey())
        .param("team", r.teamId()).param("repo", r.repositoryId()).param("env", r.environment())
        .param("manifest", json.write(r.manifest())).param("digest", r.manifestDigest())
        .param("status", r.status().name()).param("created", Jdbc.ts(r.createdAt()))
        .update();
    history(r.id(), null, r.status(), "created", null, r.createdAt());
  }

  public Optional<ManifestRequest> findById(String id) {
    return jdbc.sql("SELECT * FROM manifest_request WHERE id = :id").param("id", id).query(mapper).optional();
  }

  public Optional<ManifestRequest> findByIdempotency(String principalId, String key) {
    return jdbc.sql("SELECT * FROM manifest_request WHERE principal_id = :p AND idempotency_key = :k")
        .param("p", principalId).param("k", key).query(mapper).optional();
  }

  public List<ManifestRequest> findByStatus(RequestStatus status, int limit) {
    return jdbc.sql("SELECT * FROM manifest_request WHERE status = :s ORDER BY updated_at LIMIT :l")
        .param("s", status.name()).param("l", limit).query(mapper).list();
  }

  /**
   * Optimistic transition: succeeds only when the row still has the expected version. Returns the new version.
   * Callers must have checked {@code RequestLifecycle.canTransition} first.
   */
  public boolean transition(String id, long expectedVersion, RequestStatus from, RequestStatus to, String reason,
                            String eventId, String planId, String planDigest, Long stateVersion, Instant now) {
    int updated = jdbc.sql("""
        UPDATE manifest_request SET status = :to, version = version + 1, updated_at = :now,
          plan_id = COALESCE(cast(:planId as text), plan_id), plan_digest = COALESCE(cast(:planDigest as text), plan_digest),
          state_version = COALESCE(cast(:stateVersion as bigint), state_version),
          failure_reason = :reason,
          completed_at = CASE WHEN cast(:terminal as boolean) THEN :now ELSE completed_at END
        WHERE id = :id AND version = :version
        """)
        .param("to", to.name()).param("now", Jdbc.ts(now)).param("planId", planId).param("planDigest", planDigest)
        .param("stateVersion", stateVersion).param("reason", reason).param("terminal", to.terminal())
        .param("id", id).param("version", expectedVersion)
        .update();
    if (updated == 1) {
      history(id, from, to, reason, eventId, now);
      return true;
    }
    return false;
  }

  private void history(String requestId, RequestStatus from, RequestStatus to, String reason, String eventId, Instant at) {
    jdbc.sql("INSERT INTO request_status_history (request_id, from_status, to_status, reason, event_id, occurred_at) VALUES (:r, :f, :t, :reason, :e, :at)")
        .param("r", requestId).param("f", from == null ? null : from.name()).param("t", to.name())
        .param("reason", reason).param("e", eventId).param("at", Jdbc.ts(at)).update();
  }

  public record HistoryEntry(String fromStatus, String toStatus, String reason, String eventId, Instant occurredAt) {}

  public List<HistoryEntry> history(String requestId) {
    return jdbc.sql("SELECT * FROM request_status_history WHERE request_id = :r ORDER BY occurred_at, id")
        .param("r", requestId)
        .query((rs, i) -> new HistoryEntry(rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
            rs.getString("event_id"), Jdbc.instant(rs, "occurred_at")))
        .list();
  }
}

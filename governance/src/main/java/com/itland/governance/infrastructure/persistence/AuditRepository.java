package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AuditRepository {

  public record AuditEntry(long id, Instant occurredAt, String actorId, String action, String targetType, String targetId,
                           String requestId, Map<String, Object> details) {}

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public AuditRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void insert(Instant at, String actor, String action, String targetType, String targetId, String requestId, Map<String, Object> details) {
    jdbc.sql("INSERT INTO audit_record (occurred_at, actor_id, action, target_type, target_id, request_id, details) VALUES (:at, :actor, :action, :tt, :ti, :r, cast(:d as jsonb))")
        .param("at", Jdbc.ts(at)).param("actor", actor).param("action", action).param("tt", targetType).param("ti", targetId)
        .param("r", requestId).param("d", json.write(details == null ? Map.of() : details)).update();
  }

  public List<AuditEntry> query(String targetType, String targetId, String requestId, String actor, Instant since, int limit) {
    return jdbc.sql("""
        SELECT * FROM audit_record
        WHERE (cast(:tt as text) IS NULL OR target_type = :tt) AND (cast(:ti as text) IS NULL OR target_id = :ti)
          AND (cast(:r as text) IS NULL OR request_id = :r) AND (cast(:actor as text) IS NULL OR actor_id = :actor)
          AND (cast(:since as timestamptz) IS NULL OR occurred_at >= :since)
        ORDER BY occurred_at DESC, id DESC LIMIT :limit
        """)
        .param("tt", targetType).param("ti", targetId).param("r", requestId).param("actor", actor)
        .param("since", Jdbc.ts(since)).param("limit", limit)
        .query((rs, i) -> new AuditEntry(rs.getLong("id"), Jdbc.instant(rs, "occurred_at"), rs.getString("actor_id"), rs.getString("action"),
            rs.getString("target_type"), rs.getString("target_id"), rs.getString("request_id"), json.readMap(rs.getString("details"))))
        .list();
  }
}

package com.itland.governance.infrastructure.persistence;

import com.itland.governance.configuration.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class OutboxRepository {

  public record OutboxMessage(String id, String topic, String key, String recordType, String payloadJson, Map<String, Object> headers,
                              String requestId, int attempts) {}

  private final JdbcClient jdbc;
  private Json json; // not final: referenced from row-mapper field initializers

  public OutboxRepository(JdbcClient jdbc, Json json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<OutboxMessage> mapper = (rs, i) -> new OutboxMessage(rs.getString("id"), rs.getString("topic"), rs.getString("message_key"),
      rs.getString("record_type"), rs.getString("payload"), json.readMap(rs.getString("headers")), rs.getString("request_id"), rs.getInt("attempts"));

  public void enqueue(String id, String topic, String key, String recordType, String payloadJson, Map<String, String> headers, String requestId, Instant now) {
    jdbc.sql("""
        INSERT INTO kafka_outbox (id, topic, message_key, record_type, payload, headers, request_id, status, attempts, next_attempt_at, created_at)
        VALUES (:id, :topic, :key, :type, cast(:payload as jsonb), cast(:headers as jsonb), :request, 'PENDING', 0, :now, :now)
        """)
        .param("id", id).param("topic", topic).param("key", key).param("type", recordType).param("payload", payloadJson)
        .param("headers", json.write(headers)).param("request", requestId).param("now", Jdbc.ts(now)).update();
  }

  /** Claims due messages with SKIP LOCKED so several relays can run safely. Must run inside a transaction. */
  public List<OutboxMessage> claimDue(Instant now, int limit) {
    return jdbc.sql("SELECT * FROM kafka_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY created_at LIMIT :l FOR UPDATE SKIP LOCKED")
        .param("now", Jdbc.ts(now)).param("l", limit).query(mapper).list();
  }

  public void markSent(String id, Instant now) {
    jdbc.sql("UPDATE kafka_outbox SET status = 'SENT', sent_at = :now, last_error = NULL WHERE id = :id").param("now", Jdbc.ts(now)).param("id", id).update();
  }

  public void markRetry(String id, Instant nextAttempt, String error) {
    jdbc.sql("UPDATE kafka_outbox SET attempts = attempts + 1, next_attempt_at = :next, last_error = :err WHERE id = :id")
        .param("next", Jdbc.ts(nextAttempt)).param("err", shorten(error)).param("id", id).update();
  }

  public void markFailed(String id, String error) {
    jdbc.sql("UPDATE kafka_outbox SET status = 'FAILED', attempts = attempts + 1, last_error = :err WHERE id = :id").param("err", shorten(error)).param("id", id).update();
  }

  public long countByStatus(String status) {
    return jdbc.sql("SELECT count(*) FROM kafka_outbox WHERE status = :s").param("s", status).query(Long.class).single();
  }

  public List<OutboxMessage> findByRequest(String requestId) {
    return jdbc.sql("SELECT * FROM kafka_outbox WHERE request_id = :r ORDER BY created_at").param("r", requestId).query(mapper).list();
  }

  static String shorten(String s) {
    return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
  }
}

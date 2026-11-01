package com.itland.governance.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class DeadLetterRepository {

  public record DeadLetter(long id, String topic, int partition, long offset, String key, String eventId, String requestId, String reason,
                           String error, Instant recordedAt, Instant resolvedAt) {}

  private final JdbcClient jdbc;

  public DeadLetterRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(String topic, int partition, long offset, String key, String eventId, String requestId, String reason, String error, byte[] raw, Instant now) {
    jdbc.sql("""
        INSERT INTO kafka_dead_letter (topic, partition_no, offset_no, message_key, event_id, request_id, reason, error, raw_value, recorded_at)
        VALUES (:t, :p, :o, :k, :e, :r, :reason, :err, :raw, :now)
        """)
        .param("t", topic).param("p", partition).param("o", offset).param("k", key).param("e", eventId).param("r", requestId)
        .param("reason", reason).param("err", OutboxRepository.shorten(error)).param("raw", raw).param("now", Jdbc.ts(now)).update();
  }

  public long countOpen() {
    return jdbc.sql("SELECT count(*) FROM kafka_dead_letter WHERE resolved_at IS NULL").query(Long.class).single();
  }

  public List<DeadLetter> listOpen(int limit) {
    return jdbc.sql("SELECT * FROM kafka_dead_letter WHERE resolved_at IS NULL ORDER BY recorded_at LIMIT :l").param("l", limit)
        .query((rs, i) -> new DeadLetter(rs.getLong("id"), rs.getString("topic"), rs.getInt("partition_no"), rs.getLong("offset_no"), rs.getString("message_key"),
            rs.getString("event_id"), rs.getString("request_id"), rs.getString("reason"), rs.getString("error"), Jdbc.instant(rs, "recorded_at"), Jdbc.instant(rs, "resolved_at")))
        .list();
  }

  public boolean resolve(long id, Instant now) {
    return jdbc.sql("UPDATE kafka_dead_letter SET resolved_at = :now WHERE id = :id AND resolved_at IS NULL").param("now", Jdbc.ts(now)).param("id", id).update() == 1;
  }
}

package com.itland.governance.infrastructure.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class InboxRepository {

  public record InboxEntry(String eventId, String topic, int partition, long offset, String eventType, String requestId, String recordType,
                           String payloadJson, String status, int attempts) {}

  private final JdbcClient jdbc;

  public InboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<InboxEntry> mapper = (rs, i) -> new InboxEntry(rs.getString("event_id"), rs.getString("topic"), rs.getInt("partition_no"),
      rs.getLong("offset_no"), rs.getString("event_type"), rs.getString("request_id"), rs.getString("record_type"), rs.getString("payload"),
      rs.getString("status"), rs.getInt("attempts"));

  /** Returns false when the event id was seen before (idempotent consumer). */
  public boolean insertIfAbsent(String eventId, String topic, int partition, long offset, String eventType, String requestId, String recordType,
                                String payloadJson, Instant now) {
    return jdbc.sql("""
        INSERT INTO kafka_inbox (event_id, topic, partition_no, offset_no, event_type, request_id, record_type, payload, status, attempts, received_at)
        VALUES (:e, :t, :p, :o, :type, :r, :rt, cast(:payload as jsonb), 'RETRY', 0, :now)
        ON CONFLICT DO NOTHING
        """)
        .param("e", eventId).param("t", topic).param("p", partition).param("o", offset).param("type", eventType).param("r", requestId)
        .param("rt", recordType).param("payload", payloadJson).param("now", Jdbc.ts(now)).update() == 1;
  }

  public void markProcessed(String eventId, String outcome, Instant now) {
    jdbc.sql("UPDATE kafka_inbox SET status = 'PROCESSED', outcome = :o, processed_at = :now, next_attempt_at = NULL, last_error = NULL WHERE event_id = :e")
        .param("o", outcome).param("now", Jdbc.ts(now)).param("e", eventId).update();
  }

  public void markIgnored(String eventId, String outcome, Instant now) {
    jdbc.sql("UPDATE kafka_inbox SET status = 'IGNORED', outcome = :o, processed_at = :now, next_attempt_at = NULL WHERE event_id = :e")
        .param("o", outcome).param("now", Jdbc.ts(now)).param("e", eventId).update();
  }

  public void markRetry(String eventId, Instant nextAttempt, String error) {
    jdbc.sql("UPDATE kafka_inbox SET status = 'RETRY', attempts = attempts + 1, next_attempt_at = :next, last_error = :err WHERE event_id = :e")
        .param("next", Jdbc.ts(nextAttempt)).param("err", OutboxRepository.shorten(error)).param("e", eventId).update();
  }

  public void markDead(String eventId, String error, Instant now) {
    jdbc.sql("UPDATE kafka_inbox SET status = 'DEAD', attempts = attempts + 1, last_error = :err, processed_at = :now, next_attempt_at = NULL WHERE event_id = :e")
        .param("err", OutboxRepository.shorten(error)).param("now", Jdbc.ts(now)).param("e", eventId).update();
  }

  public List<InboxEntry> claimRetries(Instant now, int limit) {
    // Claim with a short lease so concurrent instances do not re-handle the same entry.
    return jdbc.sql("""
        UPDATE kafka_inbox SET next_attempt_at = :lease
        WHERE event_id IN (SELECT event_id FROM kafka_inbox WHERE status = 'RETRY' AND next_attempt_at IS NOT NULL AND next_attempt_at <= :now
                           ORDER BY next_attempt_at LIMIT :l FOR UPDATE SKIP LOCKED)
        RETURNING *
        """)
        .param("lease", Jdbc.ts(now.plusSeconds(120))).param("now", Jdbc.ts(now)).param("l", limit).query(mapper).list();
  }

  public long countByStatus(String status) {
    return jdbc.sql("SELECT count(*) FROM kafka_inbox WHERE status = :s").param("s", status).query(Long.class).single();
  }
}

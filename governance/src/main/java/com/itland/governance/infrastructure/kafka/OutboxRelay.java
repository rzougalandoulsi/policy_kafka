package com.itland.governance.infrastructure.kafka;

import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.observability.Mdc;
import com.itland.governance.infrastructure.persistence.OutboxRepository;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka. Runs on the scheduler thread pool (never on the event loop). Rows are claimed with
 * SKIP LOCKED, sent with the idempotent producer, and marked SENT in the same transaction; failures back off with a
 * bounded number of attempts and then surface as FAILED (metric + log).
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outbox;
  private final KafkaProducer<String, Object> producer;
  private final Blocking blocking;
  private final GovernanceProperties.Outbox config;
  private final GovernanceMetrics metrics;
  private final Clock clock;

  public OutboxRelay(OutboxRepository outbox, KafkaProducer<String, Object> producer, Blocking blocking, GovernanceProperties properties,
                     GovernanceMetrics metrics, Clock clock) {
    this.outbox = outbox;
    this.producer = producer;
    this.blocking = blocking;
    this.config = properties.outbox();
    this.metrics = metrics;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${governance.outbox.poll-interval:500ms}")
  public void relay() {
    int sent;
    do {
      sent = relayBatch();
    } while (sent == config.batchSize());
  }

  /** @return number of messages claimed in this batch */
  public int relayBatch() {
    return blocking.transactional(() -> {
      List<OutboxRepository.OutboxMessage> batch = outbox.claimDue(clock.instant(), config.batchSize());
      if (batch.isEmpty()) return 0;
      List<Future<RecordMetadata>> futures = new ArrayList<>(batch.size());
      for (OutboxRepository.OutboxMessage m : batch) {
        try {
          SpecificRecord value = AvroJson.decode(m.recordType(), m.payloadJson());
          ProducerRecord<String, Object> record = new ProducerRecord<>(m.topic(), m.key(), value);
          m.headers().forEach((k, v) -> record.headers().add(k, String.valueOf(v).getBytes(StandardCharsets.UTF_8)));
          futures.add(producer.send(record));
        } catch (RuntimeException e) {
          futures.add(java.util.concurrent.CompletableFuture.failedFuture(e));
        }
      }
      for (int i = 0; i < batch.size(); i++) {
        OutboxRepository.OutboxMessage m = batch.get(i);
        try (Mdc ignored = Mdc.of(m.requestId(), String.valueOf(m.headers().get("planId")))) {
          try {
            futures.get(i).get(30, TimeUnit.SECONDS);
            outbox.markSent(m.id(), clock.instant());
            metrics.recordOutboxSent(m.topic());
          } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (m.attempts() + 1 >= config.maxAttempts()) {
              log.error("Outbox message {} to {} failed permanently after {} attempts", m.id(), m.topic(), m.attempts() + 1, cause);
              outbox.markFailed(m.id(), cause.toString());
            } else {
              Duration backoff = config.retryBackoff().multipliedBy(1L << Math.min(m.attempts(), 6));
              log.warn("Outbox message {} to {} failed (attempt {}), retrying in {}: {}", m.id(), m.topic(), m.attempts() + 1, backoff, cause.toString());
              outbox.markRetry(m.id(), clock.instant().plus(backoff), cause.toString());
            }
          }
        }
      }
      return batch.size();
    });
  }
}

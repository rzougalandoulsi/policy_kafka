package com.itland.governance.infrastructure.kafka;

import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.persistence.DeadLetterRepository;
import com.itland.governance.infrastructure.persistence.RepositoryUsageRepository;
import com.itland.orchestrator.avro.RepoConfirmed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the Orchestrator's confirmed state ({@code RepoConfirmed}, compacted on teams.repo.manifest) into
 * {@code repository_usage}, the source of projected team usage and ownership conflicts for the quota and manifest
 * policies. Governance never writes to that topic. State is upserted monotonically by state version, so replays of
 * the compacted log are harmless and no inbox is needed. Undecodable records go to the dead-letter table.
 */
@Component
public class RepositoryStateConsumer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(RepositoryStateConsumer.class);

  private final GovernanceProperties.Kafka kafka;
  private final RepositoryUsageRepository usage;
  private final DeadLetterRepository deadLetters;
  private final Blocking blocking;
  private final GovernanceMetrics metrics;
  private final MeterRegistry registry;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile KafkaConsumer<String, Object> consumer;
  private Thread thread;

  public RepositoryStateConsumer(GovernanceProperties properties, RepositoryUsageRepository usage, DeadLetterRepository deadLetters, Blocking blocking,
                                 GovernanceMetrics metrics, MeterRegistry registry, Clock clock) {
    this.kafka = properties.kafka();
    this.usage = usage;
    this.deadLetters = deadLetters;
    this.blocking = blocking;
    this.metrics = metrics;
    this.registry = registry;
    this.clock = clock;
  }

  @Override
  public void start() {
    if (!kafka.consumerEnabled() || !kafka.usageConsumerEnabled() || !running.compareAndSet(false, true)) return;
    thread = Thread.ofPlatform().name("gov-usage-consumer").start(this::run);
  }

  @Override
  public void stop() {
    running.set(false);
    KafkaConsumer<String, Object> c = consumer;
    if (c != null) c.wakeup();
    if (thread != null) {
      try { thread.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 10;
  }

  private void run() {
    try (KafkaConsumer<String, Object> c = KafkaClientFactory.newConsumer(kafka, kafka.usageConsumerGroup())) {
      consumer = c;
      new KafkaClientMetrics(c, List.of(io.micrometer.core.instrument.Tag.of("consumer", "usage"))).bindTo(registry);
      c.subscribe(List.of(kafka.repositoryStateTopic()));
      log.info("Consuming {} as group {} (confirmed state for usage projection)", kafka.repositoryStateTopic(), kafka.usageConsumerGroup());
      while (running.get()) {
        try {
          ConsumerRecords<String, Object> records = c.poll(Duration.ofMillis(500));
          for (ConsumerRecord<String, Object> record : records) {
            handle(record);
          }
          if (!records.isEmpty()) {
            c.commitSync();
          }
        } catch (RecordDeserializationException e) {
          poison(e);
          c.seek(e.topicPartition(), e.offset() + 1);
        } catch (WakeupException e) {
          if (running.get()) throw e;
        } catch (RuntimeException e) {
          log.error("Usage consumer loop error; continuing after short pause", e);
          sleep();
        }
      }
    } catch (RuntimeException e) {
      log.error("Usage consumer thread terminated unexpectedly", e);
    } finally {
      consumer = null;
      running.set(false);
    }
  }

  void handle(ConsumerRecord<String, Object> record) {
    if (record.value() == null) {
      // Tombstone on the compacted topic: the repository was removed; nothing to count any more.
      metrics.recordUsageSnapshot("IGNORED");
      return;
    }
    if (!(record.value() instanceof RepoConfirmed confirmed)) {
      log.debug("Ignoring non-RepoConfirmed record {} at {}-{}", record.value().getClass().getName(), record.partition(), record.offset());
      metrics.recordUsageSnapshot("IGNORED");
      return;
    }
    String environment = confirmed.getEnvironment() == null ? "" : confirmed.getEnvironment();
    boolean applied = blocking.transactional(() -> usage.upsertConfirmed(confirmed.getTeamId(), confirmed.getRepoId(), environment, confirmed.getStateVersion(),
        List.copyOf(confirmed.getTopics()), List.copyOf(confirmed.getPools()), List.copyOf(confirmed.getRbacsKey()), clock.instant()));
    metrics.recordUsageSnapshot(applied ? "APPLIED" : "STALE");
    if (applied) {
      log.info("Confirmed state {}/{}/{} at version {}: {} topics, {} pools, {} bindings", confirmed.getTeamId(), confirmed.getRepoId(), environment,
          confirmed.getStateVersion(), confirmed.getTopics().size(), confirmed.getPools().size(), confirmed.getRbacsKey().size());
    }
  }

  private void poison(RecordDeserializationException e) {
    ByteBuffer value = e.valueBuffer();
    byte[] raw = null;
    if (value != null) {
      raw = new byte[value.remaining()];
      value.duplicate().get(raw);
    }
    byte[] rawFinal = raw;
    log.error("Poison record at {} offset {}: {}", e.topicPartition(), e.offset(), e.getMessage());
    blocking.transactionalVoid(() -> deadLetters.record(e.topicPartition().topic(), e.topicPartition().partition(), e.offset(), null, null, null,
        "DESERIALIZATION", e.getMessage(), rawFinal, clock.instant()));
    metrics.recordDeadLetter("DESERIALIZATION");
  }

  private static void sleep() {
    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }
}

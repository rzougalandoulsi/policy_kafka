package com.itland.governance.infrastructure.kafka;

import com.itland.governance.application.lifecycle.ManifestEventHandler;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.persistence.DeadLetterRepository;
import com.itland.governance.infrastructure.persistence.InboxRepository;
import com.itland.platform.avro.ManifestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Idempotent inbox: a new event is recorded and handled in one transaction. Retryable failures (OPA errors, optimistic
 * lock conflicts) leave the entry in RETRY with a backoff; the retry job re-handles from the stored payload. After the
 * configured attempts the entry is DEAD and the handler's fail-closed fallback runs.
 */
@Component
public class InboxProcessor {

  private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

  private final InboxRepository inbox;
  private final DeadLetterRepository deadLetters;
  private final ManifestEventHandler handler;
  private final Blocking blocking;
  private final GovernanceMetrics metrics;
  private final GovernanceProperties.Workflow workflow;
  private final Clock clock;

  public InboxProcessor(InboxRepository inbox, DeadLetterRepository deadLetters, ManifestEventHandler handler, Blocking blocking,
                        GovernanceMetrics metrics, GovernanceProperties properties, Clock clock) {
    this.inbox = inbox;
    this.deadLetters = deadLetters;
    this.handler = handler;
    this.blocking = blocking;
    this.metrics = metrics;
    this.workflow = properties.workflow();
    this.clock = clock;
  }

  public void processNew(String topic, int partition, long offset, ManifestEvent event) {
    boolean fresh = blocking.transactional(() -> inbox.insertIfAbsent(event.getEventId(), topic, partition, offset, event.getEventType().name(),
        event.getRequestId(), ManifestEvent.class.getName(), AvroJson.encode(event), clock.instant()));
    if (!fresh) {
      log.info("Duplicate event {} ({}) for request {}; already in inbox", event.getEventId(), event.getEventType(), event.getRequestId());
      metrics.recordInbox(event.getEventType().name(), "DUPLICATE");
      return;
    }
    attempt(event, 0, topic, partition, offset);
  }

  public void attempt(ManifestEvent event, int previousAttempts, String topic, int partition, long offset) {
    try {
      ManifestEventHandler.Outcome outcome = blocking.transactional(() -> {
        ManifestEventHandler.Outcome o = handler.handle(event);
        if (o.ignored()) inbox.markIgnored(event.getEventId(), o.code(), clock.instant());
        else inbox.markProcessed(event.getEventId(), o.code(), clock.instant());
        return o;
      });
      metrics.recordInbox(event.getEventType().name(), outcome.code());
    } catch (ManifestEventHandler.RetryableException e) {
      int attempts = previousAttempts + 1;
      if (attempts >= workflow.maxEvaluationAttempts()) {
        log.error("Event {} for request {} failed {} times; giving up: {}", event.getEventId(), event.getRequestId(), attempts, e.getMessage());
        blocking.transactionalVoid(() -> {
          inbox.markDead(event.getEventId(), e.getMessage(), clock.instant());
          deadLetters.record(topic, partition, offset, event.getTeamId(), event.getEventId(), event.getRequestId(), "HANDLER_EXHAUSTED", e.getMessage(), null, clock.instant());
          handler.onExhausted(event, e.getMessage());
        });
        metrics.recordDeadLetter("HANDLER_EXHAUSTED");
        metrics.recordInbox(event.getEventType().name(), "DEAD");
      } else {
        Duration backoff = workflow.evaluationRetryBackoff().multipliedBy(1L << Math.min(previousAttempts, 5));
        log.warn("Event {} for request {} will be retried in {} (attempt {}): {}", event.getEventId(), event.getRequestId(), backoff, attempts, e.getMessage());
        blocking.transactionalVoid(() -> inbox.markRetry(event.getEventId(), clock.instant().plus(backoff), e.getMessage()));
        metrics.recordInbox(event.getEventType().name(), "RETRY");
      }
    } catch (RuntimeException e) {
      // Unexpected bug: never block the partition. Record and move on (fail closed: the request stays where it is).
      log.error("Unexpected failure handling event {} for request {}", event.getEventId(), event.getRequestId(), e);
      blocking.transactionalVoid(() -> {
        inbox.markDead(event.getEventId(), e.toString(), clock.instant());
        deadLetters.record(topic, partition, offset, event.getTeamId(), event.getEventId(), event.getRequestId(), "HANDLER_ERROR", e.toString(), null, clock.instant());
      });
      metrics.recordDeadLetter("HANDLER_ERROR");
    }
  }
}

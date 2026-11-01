package com.itland.governance.infrastructure.kafka;

import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.infrastructure.observability.Mdc;
import com.itland.governance.infrastructure.persistence.InboxRepository;
import com.itland.platform.avro.ManifestEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/** Re-handles inbox entries whose previous attempt failed transiently (e.g. OPA unavailable). */
@Component
public class InboxRetryJob {

  private final InboxRepository inbox;
  private final InboxProcessor processor;
  private final Blocking blocking;
  private final Clock clock;
  private final int batch;

  public InboxRetryJob(InboxRepository inbox, InboxProcessor processor, Blocking blocking, Clock clock, GovernanceProperties properties) {
    this.inbox = inbox;
    this.processor = processor;
    this.blocking = blocking;
    this.clock = clock;
    this.batch = properties.inbox().pollRecords();
  }

  @Scheduled(fixedDelayString = "${governance.inbox.retry-poll-interval:5s}", initialDelayString = "10s")
  public void retry() {
    List<InboxRepository.InboxEntry> due = blocking.transactional(() -> inbox.claimRetries(clock.instant(), batch));
    for (InboxRepository.InboxEntry entry : due) {
      ManifestEvent event = AvroJson.decode(entry.recordType(), entry.payloadJson());
      try (Mdc ignored = Mdc.of(event.getRequestId(), event.getPlanId())) {
        processor.attempt(event, entry.attempts(), entry.topic(), entry.partition(), entry.offset());
      }
    }
  }
}

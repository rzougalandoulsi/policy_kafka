package com.itland.governance.infrastructure.observability;

import com.itland.governance.configuration.Blocking;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.DeadLetterRepository;
import com.itland.governance.infrastructure.persistence.InboxRepository;
import com.itland.governance.infrastructure.persistence.OutboxRepository;
import com.itland.governance.infrastructure.persistence.QuotaReservationRepository;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Backlog gauges (approvals, outbox, inbox retries, dead letters) sampled from the database. */
@Component
public class MetricsRefreshJob {

  private final GovernanceMetrics metrics;
  private final ApprovalRepository approvals;
  private final OutboxRepository outbox;
  private final InboxRepository inbox;
  private final DeadLetterRepository deadLetters;
  private final QuotaReservationRepository reservations;
  private final Blocking blocking;
  private final Clock clock;

  public MetricsRefreshJob(GovernanceMetrics metrics, ApprovalRepository approvals, OutboxRepository outbox, InboxRepository inbox,
                           DeadLetterRepository deadLetters, QuotaReservationRepository reservations, Blocking blocking, Clock clock) {
    this.metrics = metrics;
    this.approvals = approvals;
    this.outbox = outbox;
    this.inbox = inbox;
    this.deadLetters = deadLetters;
    this.reservations = reservations;
    this.blocking = blocking;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${governance.metrics.refresh-interval:15s}", initialDelayString = "5s")
  public void refresh() {
    blocking.transactionalVoid(() -> {
      metrics.approvalsPending(approvals.countPending());
      metrics.outboxPending(outbox.countByStatus("PENDING"));
      metrics.outboxFailed(outbox.countByStatus("FAILED"));
      metrics.inboxRetry(inbox.countByStatus("RETRY"));
      metrics.deadLetters(deadLetters.countOpen());
      metrics.reservationsOpen(reservations.countOpen(clock.instant()));
    });
  }
}

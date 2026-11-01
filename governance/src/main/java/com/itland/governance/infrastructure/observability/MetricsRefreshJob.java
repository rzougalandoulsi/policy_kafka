package com.itland.governance.infrastructure.observability;

import com.itland.governance.configuration.Blocking;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.DeadLetterRepository;
import com.itland.governance.infrastructure.persistence.InboxRepository;
import com.itland.governance.infrastructure.persistence.OutboxRepository;
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
  private final Blocking blocking;

  public MetricsRefreshJob(GovernanceMetrics metrics, ApprovalRepository approvals, OutboxRepository outbox, InboxRepository inbox,
                           DeadLetterRepository deadLetters, Blocking blocking) {
    this.metrics = metrics;
    this.approvals = approvals;
    this.outbox = outbox;
    this.inbox = inbox;
    this.deadLetters = deadLetters;
    this.blocking = blocking;
  }

  @Scheduled(fixedDelayString = "${governance.metrics.refresh-interval:15s}", initialDelayString = "5s")
  public void refresh() {
    blocking.transactionalVoid(() -> {
      metrics.approvalsPending(approvals.countPending());
      metrics.outboxPending(outbox.countByStatus("PENDING"));
      metrics.outboxFailed(outbox.countByStatus("FAILED"));
      metrics.inboxRetry(inbox.countByStatus("RETRY"));
      metrics.deadLetters(deadLetters.countOpen());
    });
  }
}

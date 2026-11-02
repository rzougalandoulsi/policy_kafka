package com.itland.governance.infrastructure.observability;

import com.itland.governance.application.approvals.workflow.ApprovalProcess;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.approval.ApprovalEngine;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.DeadLetterRepository;
import com.itland.governance.infrastructure.persistence.InboxRepository;
import com.itland.governance.infrastructure.persistence.OutboxRepository;
import com.itland.governance.infrastructure.persistence.QuotaReservationRepository;

import java.time.Clock;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Backlog gauges (approvals, outbox, inbox retries, dead letters, workflow engine jobs) sampled from the database. */
@Component
public class MetricsRefreshJob {

  private final GovernanceMetrics metrics;
  private final ApprovalRepository approvals;
  private final OutboxRepository outbox;
  private final InboxRepository inbox;
  private final DeadLetterRepository deadLetters;
  private final QuotaReservationRepository reservations;
  private final RuntimeService runtime;
  private final ManagementService management;
  private final Blocking blocking;
  private final Clock clock;

  public MetricsRefreshJob(GovernanceMetrics metrics, ApprovalRepository approvals, OutboxRepository outbox, InboxRepository inbox,
                           DeadLetterRepository deadLetters, QuotaReservationRepository reservations, RuntimeService runtime,
                           ManagementService management, Blocking blocking, Clock clock) {
    this.metrics = metrics;
    this.approvals = approvals;
    this.outbox = outbox;
    this.inbox = inbox;
    this.deadLetters = deadLetters;
    this.reservations = reservations;
    this.runtime = runtime;
    this.management = management;
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
      metrics.legacyApprovalsPending(approvals.countPending(ApprovalEngine.LEGACY));
      metrics.workflowInstancesActive(runtime.createProcessInstanceQuery()
          .processDefinitionKeys(java.util.Set.of(ApprovalProcess.MANIFEST_APPROVAL, ApprovalProcess.ACTION_PLAN_APPROVAL)).count());
      metrics.workflowTimersPending(management.createTimerJobQuery().count());
      metrics.workflowDeadLetterJobs(management.createDeadLetterJobQuery().count());
    });
  }
}

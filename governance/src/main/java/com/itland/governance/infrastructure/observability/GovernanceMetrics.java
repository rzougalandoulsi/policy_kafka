package com.itland.governance.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class GovernanceMetrics {

  private final MeterRegistry registry;
  private final AtomicLong approvalsPending = new AtomicLong();
  private final AtomicLong outboxPending = new AtomicLong();
  private final AtomicLong outboxFailed = new AtomicLong();
  private final AtomicLong inboxRetry = new AtomicLong();
  private final AtomicLong deadLetters = new AtomicLong();
  private final AtomicLong policyRevisionMatch = new AtomicLong(-1);
  private final AtomicLong reservationsOpen = new AtomicLong();

  public GovernanceMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("governance.approvals.pending", approvalsPending, AtomicLong::get).register(registry);
    Gauge.builder("governance.outbox.pending", outboxPending, AtomicLong::get).register(registry);
    Gauge.builder("governance.outbox.failed", outboxFailed, AtomicLong::get).register(registry);
    Gauge.builder("governance.inbox.retry", inboxRetry, AtomicLong::get).register(registry);
    Gauge.builder("governance.kafka.deadletters.open", deadLetters, AtomicLong::get).register(registry);
    Gauge.builder("governance.policy.revision.match", policyRevisionMatch, AtomicLong::get)
        .description("1 when OPA runs the active bundle revision, 0 when it differs, -1 when unknown").register(registry);
    Gauge.builder("governance.quota.reservations.open", reservationsOpen, AtomicLong::get)
        .description("Quota reservations held by authorised plans that have not completed yet").register(registry);
  }

  /** governance_shadow_findings_total{policyId,kind}: findings of policies in shadow mode (recorded, never blocking). */
  public void recordShadowFinding(String policyId, String kind) {
    Counter.builder("governance.shadow.findings").tag("policyId", policyId).tag("kind", kind).register(registry).increment();
  }

  /** Confirmed-state snapshots consumed from teams.repo.manifest, by outcome (APPLIED | STALE | IGNORED). */
  public void recordUsageSnapshot(String outcome) {
    Counter.builder("governance.usage.snapshots").tag("outcome", outcome).register(registry).increment();
  }

  /** Stage 2 evaluations deferred because the usage snapshot was older than the plan's state version. */
  public void recordUsageStale() {
    Counter.builder("governance.usage.stale").register(registry).increment();
  }

  public void recordReservation(String outcome) {
    Counter.builder("governance.quota.reservations").tag("outcome", outcome).register(registry).increment();
  }

  public void reservationsOpen(long v) { reservationsOpen.set(v); }

  public void recordOpaEvaluation(String stage, String outcome, Duration latency) {
    Timer.builder("governance.opa.evaluation").tag("stage", stage).tag("outcome", outcome)
        .publishPercentileHistogram().register(registry).record(latency);
  }

  public void recordDecision(String stage, String type) {
    Counter.builder("governance.decisions").tag("stage", stage).tag("type", type).register(registry).increment();
  }

  public void recordOutboxSent(String topic) {
    Counter.builder("governance.outbox.sent").tag("topic", topic).register(registry).increment();
  }

  public void recordInbox(String eventType, String outcome) {
    Counter.builder("governance.inbox.events").tag("type", eventType).tag("outcome", outcome).register(registry).increment();
  }

  public void recordDeadLetter(String reason) {
    Counter.builder("governance.kafka.deadletters").tag("reason", reason).register(registry).increment();
  }

  public void approvalsPending(long v) { approvalsPending.set(v); }
  public void outboxPending(long v) { outboxPending.set(v); }
  public void outboxFailed(long v) { outboxFailed.set(v); }
  public void inboxRetry(long v) { inboxRetry.set(v); }
  public void deadLetters(long v) { deadLetters.set(v); }
  public void policyRevisionMatch(Boolean match) { policyRevisionMatch.set(match == null ? -1 : match ? 1 : 0); }

  public MeterRegistry registry() {
    return registry;
  }
}

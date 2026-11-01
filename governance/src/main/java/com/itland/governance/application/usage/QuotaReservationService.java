package com.itland.governance.application.usage;

import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.team.TeamConfiguration;
import com.itland.governance.domain.usage.QuotaReservation;
import com.itland.governance.domain.usage.UsageCounts;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.persistence.QuotaReservationRepository;
import com.itland.governance.infrastructure.persistence.RepositoryUsageRepository;
import com.itland.governance.infrastructure.persistence.TeamConfigurationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Admission control for team quotas. The caller (stage 2 in {@code ManifestEventHandler} and
 * {@code ApprovalReleaseService}) takes the team's row lock with {@link #lockTeam} inside the transaction that will
 * write PLAN_AUTHORIZED and the outbox row, evaluates (so the quota rule sees every committed reservation of the team),
 * then {@link #reserve}s the plan's deltas. Two simultaneously approved plans of one team are therefore serialised and
 * the second one is denied by {@code TEAM_QUOTA_EXCEEDED} rather than slipping through. Reservations are released on
 * terminal events, on approval expiry and by TTL (the authorisation validity).
 */
@Service
public class QuotaReservationService {

  private static final Logger log = LoggerFactory.getLogger(QuotaReservationService.class);

  private final QuotaReservationRepository reservations;
  private final TeamConfigurationRepository teams;
  private final RepositoryUsageRepository usage;
  private final GovernanceMetrics metrics;
  private final Blocking blocking;
  private final GovernanceProperties.Workflow workflow;
  private final Clock clock;

  public QuotaReservationService(QuotaReservationRepository reservations, TeamConfigurationRepository teams, RepositoryUsageRepository usage,
                                 GovernanceMetrics metrics, Blocking blocking, GovernanceProperties properties, Clock clock) {
    this.reservations = reservations;
    this.teams = teams;
    this.usage = usage;
    this.metrics = metrics;
    this.blocking = blocking;
    this.workflow = properties.workflow();
    this.clock = clock;
  }

  /**
   * Serialises admissions of one team (SELECT ... FOR UPDATE on its configuration row). Empty when the team has no
   * stored configuration: it then has no quotas, so nothing needs protecting. Must run inside a transaction.
   */
  public Optional<TeamConfiguration> lockTeam(String teamId) {
    return teams.lockById(teamId);
  }

  /** Reserves the plan's deltas until a terminal event or the authorisation validity passes. No row for a zero delta. */
  public Optional<QuotaReservation> reserve(ManifestRequest request, ProposedPlan plan, String decisionId) {
    UsageCounts deltas = UsageMath.actionsDelta(plan.actions(), usage.find(request.teamId(), request.repositoryId(), request.environment()).orElse(null));
    if (deltas.isZero()) return Optional.empty();
    Instant now = clock.instant();
    QuotaReservation r = new QuotaReservation("qr-" + UUID.randomUUID(), request.teamId(), request.environment(), request.id(), plan.planId(), decisionId,
        deltas, now, now.plus(workflow.authorizationValidity()), null, null);
    reservations.insert(r);
    metrics.recordReservation("RESERVED");
    log.info("Reserved {} for request {} plan {} (team {}, {})", deltas.sorted(), request.id(), plan.planId(), request.teamId(), request.environment());
    return Optional.of(r);
  }

  /** Releases whatever the request still holds (idempotent). Caller's transaction. */
  public int release(String requestId, String reason) {
    int n = reservations.releaseByRequest(requestId, reason, clock.instant());
    if (n > 0) {
      metrics.recordReservation("RELEASED");
      log.info("Released {} quota reservation(s) of request {} ({})", n, requestId, reason);
    }
    return n;
  }

  @Scheduled(fixedDelayString = "${governance.usage.reservation-sweep-interval:30s}", initialDelayString = "20s")
  public void expire() {
    int n = blocking.transactional(() -> reservations.releaseExpired(clock.instant()));
    if (n > 0) {
      metrics.recordReservation("EXPIRED");
      log.info("Released {} expired quota reservation(s)", n);
    }
  }
}

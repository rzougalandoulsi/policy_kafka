package com.itland.governance.application.evaluation;

import com.itland.governance.application.exceptions.ExceptionResolver;
import com.itland.governance.application.policy.PolicyConfigurationService;
import com.itland.governance.application.teams.TeamConfigurationService;
import com.itland.governance.application.usage.UsageProjector;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.exception.PolicyException;
import com.itland.governance.domain.policy.PolicyRelease;
import com.itland.governance.domain.policy.PolicySnapshot;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.team.TeamConfiguration;
import com.itland.governance.domain.usage.UsageSnapshot;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.opa.OpaClient;
import com.itland.governance.infrastructure.opa.OpaEvaluation;
import com.itland.governance.infrastructure.opa.OpaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One OPA evaluation = one {@link GovernanceDecision}. Loads a consistent policy snapshot, the caller's valid
 * exceptions, the team configuration and the projected usage, calls OPA with input contract 2.0, aggregates
 * deterministically and verifies that OPA evaluated the active release with a supported contract major.
 * Any failure yields an ERROR decision (fail closed); it never throws for policy reasons.
 */
@Service
public class PolicyEvaluationService {

  private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);

  private final OpaClient opa;
  private final PolicyConfigurationService configurations;
  private final ExceptionResolver exceptions;
  private final TeamConfigurationService teams;
  private final UsageProjector usage;
  private final Blocking blocking;
  private final GovernanceMetrics metrics;
  private final GovernanceProperties.Opa opaProperties;
  private final GovernanceProperties.Usage usageProperties;
  private final Clock clock;

  public PolicyEvaluationService(OpaClient opa, PolicyConfigurationService configurations, ExceptionResolver exceptions,
                                 TeamConfigurationService teams, UsageProjector usage, Blocking blocking,
                                 GovernanceMetrics metrics, GovernanceProperties properties, Clock clock) {
    this.opa = opa;
    this.configurations = configurations;
    this.exceptions = exceptions;
    this.teams = teams;
    this.usage = usage;
    this.blocking = blocking;
    this.metrics = metrics;
    this.opaProperties = properties.opa();
    this.usageProperties = properties.usage();
    this.clock = clock;
  }

  /** Stage 1: the manifest as submitted. Stage 2: the proposed plan plus the original request context. */
  public Mono<GovernanceDecision> evaluate(GovernanceStage stage, ManifestRequest request, Set<String> principalRoles, ProposedPlan plan) {
    String digest = stage == GovernanceStage.REQUEST ? request.manifestDigest() : plan.planDigest();
    String planId = plan == null ? null : plan.planId();
    record Prepared(PolicySnapshot snapshot, List<PolicyException> exceptions, TeamConfiguration team, UsageSnapshot usage, Instant now) {}
    Instant started = clock.instant();

    return blocking.call(() -> {
          Instant now = clock.instant();
          PolicySnapshot snapshot = configurations.activeSnapshot();
          List<PolicyException> excs = exceptions.resolve(request.principalId(), request.teamId(), request.environment(), now);
          TeamConfiguration team = teams.effective(request.teamId(), snapshot.configuration());
          UsageSnapshot projected = usage.project(request, stage, plan, now);
          return new Prepared(snapshot, excs, team, projected, now);
        })
        .flatMap(prepared -> {
          PolicyRelease release = prepared.snapshot().release();
          if (opaProperties.verifyRevision() && (release == null || !release.complete(opaProperties.bundles()))) {
            Set<String> missing = release == null ? new java.util.TreeSet<>(opaProperties.bundles()) : release.missing(opaProperties.bundles());
            return Mono.just(error(stage, request, planId, digest, prepared.snapshot(), started, "No complete ACTIVE policy release; missing bundles " + missing));
          }
          if (plan != null && usageProperties.requireFreshSnapshot() && prepared.usage().staleFor(plan.stateVersion())) {
            metrics.recordUsageStale();
            String have = prepared.usage().repositoryStateVersion().isPresent() ? String.valueOf(prepared.usage().repositoryStateVersion().getAsLong()) : "none";
            return Mono.just(error(stage, request, planId, digest, prepared.snapshot(), started,
                "Usage snapshot for repository " + request.repositoryId() + " is at state version " + have + " but the plan was computed against "
                    + plan.stateVersion() + "; waiting for the confirmed state on the repository state topic"));
          }
          Map<String, Object> input = OpaInputAssembler.build(stage, request.principalId(), principalRoles, request.manifest(),
              request.id(), request.manifestDigest(), plan, prepared.snapshot(), prepared.exceptions(), prepared.team(), prepared.usage(),
              prepared.now(), opaProperties.contractVersion());
          Set<String> known = prepared.exceptions().stream().map(PolicyException::id).collect(Collectors.toSet());
          return opa.evaluate(input)
              .map(evaluation -> toDecision(stage, request, planId, digest, prepared.snapshot(), evaluation, known, started))
              .onErrorResume(OpaException.class, e -> {
                log.warn("OPA evaluation failed for request {} stage {}: {}", request.id(), stage, e.getMessage());
                return Mono.just(error(stage, request, planId, digest, prepared.snapshot(), started, e.getMessage()));
              });
        })
        .onErrorResume(e -> {
          log.error("Policy evaluation failed for request {} stage {}", request.id(), stage, e);
          return Mono.just(error(stage, request, planId, digest, null, started, "Evaluation failed: " + e.getMessage()));
        })
        .doOnNext(d -> {
          metrics.recordOpaEvaluation(stage.name(), d.type().name(), Duration.between(started, clock.instant()));
          metrics.recordDecision(stage.name(), d.type().name());
          d.shadowViolations().forEach(v -> metrics.recordShadowFinding(v.policyId(), "VIOLATION"));
          d.shadowApprovals().forEach(a -> metrics.recordShadowFinding(a.policyId(), "APPROVAL"));
        });
  }

  private GovernanceDecision toDecision(GovernanceStage stage, ManifestRequest request, String planId, String digest, PolicySnapshot snapshot,
                                        OpaEvaluation evaluation, Set<String> knownExceptions, Instant started) {
    long latency = Duration.between(started, clock.instant()).toMillis();
    PolicyRelease release = snapshot.release();
    if (!Boolean.TRUE.equals(evaluation.result().ready())) {
      return error(stage, request, planId, digest, snapshot, started,
          "Policy set not ready: OPA composition reports bundles " + evaluation.result().bundles());
    }
    String major = evaluation.result().contractMajor();
    if (!opaProperties.supportedContractMajors().contains(major)) {
      return error(stage, request, planId, digest, snapshot, started,
          "Policy contract major " + major + " is not supported by this Governance build (accepts " + opaProperties.supportedContractMajors() + ")");
    }
    if (opaProperties.verifyRevision()) {
      for (String name : opaProperties.bundles()) {
        String expected = release.revisions().get(name);
        String opaRevision = evaluation.bundleRevision(name);
        if (!expected.equals(opaRevision)) {
          return error(stage, request, planId, digest, snapshot, started,
              "Policy revision mismatch for bundle " + name + ": active=" + expected + " opa=" + opaRevision);
        }
      }
    }
    DecisionAggregator.Aggregate agg = DecisionAggregator.aggregate(evaluation.result(), knownExceptions);
    if (!agg.consistentWithOpa()) {
      return error(stage, request, planId, digest, snapshot, started,
          "OPA decision '" + evaluation.result().decision() + "' is inconsistent with aggregated violations (" + agg.type() + ")");
    }
    return new GovernanceDecision("dec-" + UUID.randomUUID(), request.id(), planId, stage, agg.type(),
        release == null ? "" : release.id(), release == null ? Map.of() : release.revisions(), snapshot.configurationVersion(), digest,
        agg.matchedPolicies(), agg.violations(), agg.approvals(), agg.appliedExceptions(), null, latency, clock.instant());
  }

  private GovernanceDecision error(GovernanceStage stage, ManifestRequest request, String planId, String digest, PolicySnapshot snapshot,
                                   Instant started, String message) {
    PolicyRelease release = snapshot == null ? null : snapshot.release();
    return new GovernanceDecision("dec-" + UUID.randomUUID(), request.id(), planId, stage, DecisionType.ERROR,
        release == null ? "" : release.id(), release == null ? Map.of() : release.revisions(),
        snapshot == null ? 0 : snapshot.configurationVersion(), digest, List.of(), List.of(), List.of(), List.of(), message,
        Duration.between(started, clock.instant()).toMillis(), clock.instant());
  }
}

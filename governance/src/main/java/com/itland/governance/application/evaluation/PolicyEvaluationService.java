package com.itland.governance.application.evaluation;

import com.itland.governance.application.exceptions.ExceptionResolver;
import com.itland.governance.application.policy.PolicyConfigurationService;
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
 * One OPA evaluation = one {@link GovernanceDecision}. Loads a consistent policy snapshot and the caller's valid
 * exceptions, calls OPA, aggregates deterministically and verifies that OPA evaluated the active bundle revision.
 * Any failure yields an ERROR decision (fail closed); it never throws for policy reasons.
 */
@Service
public class PolicyEvaluationService {

  private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);

  private final OpaClient opa;
  private final PolicyConfigurationService configurations;
  private final ExceptionResolver exceptions;
  private final Blocking blocking;
  private final GovernanceMetrics metrics;
  private final GovernanceProperties.Opa opaProperties;
  private final Clock clock;

  public PolicyEvaluationService(OpaClient opa, PolicyConfigurationService configurations, ExceptionResolver exceptions, Blocking blocking,
                                 GovernanceMetrics metrics, GovernanceProperties properties, Clock clock) {
    this.opa = opa;
    this.configurations = configurations;
    this.exceptions = exceptions;
    this.blocking = blocking;
    this.metrics = metrics;
    this.opaProperties = properties.opa();
    this.clock = clock;
  }

  /** Stage 1: the manifest as submitted. Stage 2: the proposed plan plus the original request context. */
  public Mono<GovernanceDecision> evaluate(GovernanceStage stage, ManifestRequest request, Set<String> principalRoles, ProposedPlan plan) {
    String digest = stage == GovernanceStage.REQUEST ? request.manifestDigest() : plan.planDigest();
    String planId = plan == null ? null : plan.planId();
    record Prepared(PolicySnapshot snapshot, List<PolicyException> exceptions) {}
    Instant started = clock.instant();

    return blocking.call(() -> {
          PolicySnapshot snapshot = configurations.activeSnapshot();
          List<PolicyException> excs = exceptions.resolve(request.principalId(), request.teamId(), request.environment(), clock.instant());
          return new Prepared(snapshot, excs);
        })
        .flatMap(prepared -> {
          PolicyRelease release = prepared.snapshot().release();
          if (opaProperties.verifyRevision() && (release == null || !release.complete(opaProperties.bundles()))) {
            Set<String> missing = release == null ? new java.util.TreeSet<>(opaProperties.bundles()) : release.missing(opaProperties.bundles());
            return Mono.just(error(stage, request, planId, digest, prepared.snapshot(), started, "No complete ACTIVE policy release; missing bundles " + missing));
          }
          Map<String, Object> input = OpaInputAssembler.build(stage, request.principalId(), principalRoles, request.manifest(),
              request.id(), request.manifestDigest(), plan, prepared.snapshot(), prepared.exceptions());
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

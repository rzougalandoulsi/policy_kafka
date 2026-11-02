package com.itland.governance.application.submission;

import com.itland.governance.application.approvals.workflow.ApprovalWorkflowService;
import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.evaluation.PolicyEvaluationService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.manifest.ManifestValidator;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.RequestLifecycle;
import com.itland.governance.domain.request.RequestStatus;
import com.itland.governance.infrastructure.kafka.ManifestCommandPublisher;
import com.itland.governance.infrastructure.persistence.DecisionRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.security.CallerContext;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 1: authenticate (done upstream), authorize team/environment, validate structure, persist the request,
 * evaluate with OPA, persist the decision and either reject, open an approval, or hand over to the Orchestrator
 * through the outbox. Governance never computes the diff.
 */
@Service
public class ManifestSubmissionService {

  private static final Logger log = LoggerFactory.getLogger(ManifestSubmissionService.class);

  public record Outcome(ManifestRequest request, GovernanceDecision decision, boolean replayed) {}

  private final ManifestRequestRepository requests;
  private final DecisionRepository decisions;
  private final PolicyEvaluationService evaluation;
  private final ApprovalWorkflowService approvals;
  private final ManifestCommandPublisher publisher;
  private final TeamAuthorization authorization;
  private final AuditService audit;
  private final Blocking blocking;
  private final Clock clock;

  public ManifestSubmissionService(ManifestRequestRepository requests, DecisionRepository decisions, PolicyEvaluationService evaluation,
                                   ApprovalWorkflowService approvals, ManifestCommandPublisher publisher, TeamAuthorization authorization,
                                   AuditService audit, Blocking blocking, Clock clock) {
    this.requests = requests;
    this.decisions = decisions;
    this.evaluation = evaluation;
    this.approvals = approvals;
    this.publisher = publisher;
    this.authorization = authorization;
    this.audit = audit;
    this.blocking = blocking;
    this.clock = clock;
  }

  public Mono<Outcome> submit(CallerContext caller, Manifest manifest, String idempotencyKey) {
    List<String> errors = ManifestValidator.validate(manifest);
    if (!errors.isEmpty()) {
      return Mono.error(new SubmissionExceptions.ManifestValidationException(errors));
    }
    authorization.requireSubmit(caller, manifest.teamId(), manifest.environment());
    String key = idempotencyKey == null || idempotencyKey.isBlank() ? "auto-" + UUID.randomUUID() : idempotencyKey;
    String digest = ManifestMapper.digest(manifest);

    record Start(ManifestRequest request, boolean replay) {}
    return blocking.inTransaction(() -> {
          Optional<ManifestRequest> existing = requests.findByIdempotency(caller.principalId(), key);
          if (existing.isPresent()) {
            ManifestRequest r = existing.get();
            if (!r.manifestDigest().equals(digest)) throw new SubmissionExceptions.IdempotencyConflictException(r.id());
            return new Start(r, r.status() != RequestStatus.EVALUATION_ERROR);
          }
          Instant now = clock.instant();
          ManifestRequest created = new ManifestRequest("req-" + UUID.randomUUID(), caller.principalId(), key, manifest.teamId(),
              manifest.repositoryId(), manifest.environment(), manifest, digest, RequestStatus.RECEIVED, null, null, null, null, 0, now, now, null);
          try {
            requests.insert(created);
          } catch (DuplicateKeyException race) {
            ManifestRequest r = requests.findByIdempotency(caller.principalId(), key).orElseThrow();
            if (!r.manifestDigest().equals(digest)) throw new SubmissionExceptions.IdempotencyConflictException(r.id());
            return new Start(r, true);
          }
          audit.record(caller.principalId(), "MANIFEST_SUBMITTED", "manifest_request", created.id(), created.id(),
              Map.of("teamId", manifest.teamId(), "repositoryId", manifest.repositoryId(), "environment", manifest.environment(), "manifestDigest", digest));
          return new Start(created, false);
        })
        .flatMap(start -> {
          if (start.replay()) {
            return blocking.call(() -> new Outcome(start.request(), latestDecision(start.request().id()), true));
          }
          return evaluation.evaluate(GovernanceStage.REQUEST, start.request(), caller.roles(), null)
              .flatMap(decision -> blocking.inTransaction(() -> apply(start.request().id(), decision, caller)))
              // The ERROR decision and EVALUATION_ERROR state are committed above; only now fail the HTTP call (503).
              .flatMap(outcome -> outcome.decision().type() == DecisionType.ERROR
                  ? Mono.error(new SubmissionExceptions.EvaluationUnavailableException(outcome.decision()))
                  : Mono.just(outcome));
        });
  }

  private Outcome apply(String requestId, GovernanceDecision decision, CallerContext caller) {
    ManifestRequest request = requests.findById(requestId).orElseThrow();
    decisions.insert(decision);
    Instant now = clock.instant();
    RequestStatus target = switch (decision.type()) {
      case DENY -> RequestStatus.STAGE1_DENIED;
      case REQUIRE_APPROVAL -> RequestStatus.PENDING_APPROVAL_STAGE1;
      case ALLOW -> RequestStatus.SUBMITTED;
      case ERROR -> RequestStatus.EVALUATION_ERROR;
    };
    if (decision.type() == DecisionType.ERROR && request.status() == RequestStatus.EVALUATION_ERROR) {
      return new Outcome(request, decision, false); // still failing: stays retryable; caller fails closed
    }
    if (!RequestLifecycle.canTransition(request.status(), target)) {
      log.warn("Request {} is {} and cannot move to {}; decision {} recorded only", requestId, request.status(), target, decision.id());
      return new Outcome(request, decision, false);
    }
    String reason = switch (decision.type()) {
      case DENY -> "Denied by policy: " + decision.blockingViolations().stream().map(v -> v.policyId()).distinct().toList();
      case REQUIRE_APPROVAL -> "Approval required: " + decision.enforcedApprovals().stream().map(a -> a.policyId()).distinct().toList();
      case ALLOW -> "Stage 1 allowed; submitted to orchestrator";
      case ERROR -> decision.errorMessage();
    };
    if (!requests.transition(requestId, request.version(), request.status(), target, reason, null, null, null, null, now)) {
      throw new IllegalStateException("Concurrent modification of request " + requestId);
    }
    switch (decision.type()) {
      case ALLOW -> publisher.enqueueSubmit(request);
      case REQUIRE_APPROVAL -> approvals.open(request, decision, GovernanceStage.REQUEST, decision.enforcedApprovals(), request.manifestDigest(), null, null);
      default -> { }
    }
    audit.record(caller.principalId(), "STAGE1_DECISION", "manifest_request", requestId, requestId,
        Map.of("decisionId", decision.id(), "type", decision.type().name(), "policyRevision", decision.policyRevision(),
            "configurationVersion", decision.configurationVersion(), "violations", decision.violations().size(),
            "shadowFindings", decision.shadowViolations().size() + decision.shadowApprovals().size()));
    return new Outcome(requests.findById(requestId).orElseThrow(), decision, false);
  }

  private GovernanceDecision latestDecision(String requestId) {
    List<GovernanceDecision> list = decisions.findByRequest(requestId);
    return list.isEmpty() ? null : list.get(list.size() - 1);
  }
}

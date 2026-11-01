package com.itland.governance.api.requests;

import com.itland.governance.api.Callers;
import com.itland.governance.application.policy.NotFoundException;
import com.itland.governance.application.tracking.RequestTrackingService;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/requests")
@Tag(name = "Requests", description = "Track manifest requests, their decisions and proposed plans")
public class RequestController {

  public record RequestResponse(String requestId, String status, String teamId, String repositoryId, String environment, String principalId,
                                String manifestDigest, String planId, String planDigest, Long stateVersion, String failureReason,
                                Instant createdAt, Instant updatedAt, Instant completedAt, Manifest manifest,
                                List<ManifestRequestRepository.HistoryEntry> history) {}

  private final RequestTrackingService tracking;
  private final Callers callers;

  public RequestController(RequestTrackingService tracking, Callers callers) {
    this.tracking = tracking;
    this.callers = callers;
  }

  @GetMapping("/{requestId}")
  @Operation(summary = "Request status and history")
  public Mono<RequestResponse> get(Authentication auth, @PathVariable String requestId) {
    return tracking.get(callers.of(auth), requestId).map(v -> {
      var r = v.request();
      return new RequestResponse(r.id(), r.status().name(), r.teamId(), r.repositoryId(), r.environment(), r.principalId(), r.manifestDigest(),
          r.planId(), r.planDigest(), r.stateVersion(), r.failureReason(), r.createdAt(), r.updatedAt(), r.completedAt(), r.manifest(), v.history());
    });
  }

  @GetMapping("/{requestId}/decisions")
  @Operation(summary = "Governance decisions (stage 1 and stage 2) for the request")
  public Mono<List<GovernanceDecision>> decisions(Authentication auth, @PathVariable String requestId) {
    return tracking.decisions(callers.of(auth), requestId);
  }

  @GetMapping("/{requestId}/plan")
  @Operation(summary = "The immutable plan proposed by the orchestrator, if any")
  public Mono<ProposedPlan> plan(Authentication auth, @PathVariable String requestId) {
    return tracking.plan(callers.of(auth), requestId)
        .flatMap(p -> p.map(Mono::just).orElseGet(() -> Mono.error(new NotFoundException("No plan proposed yet for request " + requestId))));
  }
}

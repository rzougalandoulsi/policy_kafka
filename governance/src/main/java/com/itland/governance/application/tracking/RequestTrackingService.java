package com.itland.governance.application.tracking;

import com.itland.governance.application.policy.NotFoundException;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.infrastructure.persistence.DecisionRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import com.itland.governance.infrastructure.security.CallerContext;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Service
public class RequestTrackingService {

  public record RequestView(ManifestRequest request, List<ManifestRequestRepository.HistoryEntry> history) {}

  private final ManifestRequestRepository requests;
  private final DecisionRepository decisions;
  private final ProposedPlanRepository plans;
  private final TeamAuthorization authorization;
  private final Blocking blocking;

  public RequestTrackingService(ManifestRequestRepository requests, DecisionRepository decisions, ProposedPlanRepository plans,
                                TeamAuthorization authorization, Blocking blocking) {
    this.requests = requests;
    this.decisions = decisions;
    this.plans = plans;
    this.authorization = authorization;
    this.blocking = blocking;
  }

  public Mono<RequestView> get(CallerContext caller, String requestId) {
    return blocking.call(() -> new RequestView(authorized(caller, requestId), requests.history(requestId)));
  }

  public Mono<List<GovernanceDecision>> decisions(CallerContext caller, String requestId) {
    return blocking.call(() -> {
      authorized(caller, requestId);
      return decisions.findByRequest(requestId);
    });
  }

  public Mono<Optional<ProposedPlan>> plan(CallerContext caller, String requestId) {
    return blocking.call(() -> {
      ManifestRequest r = authorized(caller, requestId);
      return r.planId() == null ? plans.findLatestByRequest(requestId) : plans.findByPlanId(r.planId());
    });
  }

  private ManifestRequest authorized(CallerContext caller, String requestId) {
    ManifestRequest r = requests.findById(requestId).orElseThrow(() -> new NotFoundException("Request " + requestId + " not found"));
    authorization.requireView(caller, r.principalId(), r.teamId());
    return r;
  }
}

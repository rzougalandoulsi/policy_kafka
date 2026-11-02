package com.itland.governance.application.approvals;

import com.itland.governance.application.approvals.workflow.ApprovalReleaseSteps;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.request.ManifestRequest;
import org.springframework.stereotype.Service;

/**
 * Legacy release path (approvals with {@code engine = LEGACY}): the same checks and effects as the BPMN service tasks,
 * sequenced in Java instead of by the engine. Delegates everything to {@link ApprovalReleaseSteps}, so behaviour cannot
 * drift between the two paths while legacy approvals drain. Removed with the legacy branch (ADR 0011).
 */
@Service
public class ApprovalReleaseService {

  private final ApprovalReleaseSteps steps;

  public ApprovalReleaseService(ApprovalReleaseSteps steps) {
    this.steps = steps;
  }

  public void releaseAfterApproval(ApprovalRequest approval, ManifestRequest request, String actor) {
    if (approval.stage() == GovernanceStage.REQUEST) {
      GovernanceDecision decision = steps.revalidateStage1(approval, request);
      switch (decision.type()) {
        case ERROR -> throw ApprovalReleaseSteps.unavailable(decision);
        case DENY -> steps.denyStage1(approval, request, decision, actor);
        default -> steps.submitManifest(approval, request, decision, actor);
      }
      return;
    }
    ApprovalReleaseSteps.Stage2Revalidation result = steps.revalidateStage2(approval, request);
    if (result.stale()) {
      steps.stalePlan(approval, request, result.plan(), result.latestStateVersion(), actor);
      return;
    }
    GovernanceDecision decision = result.decision();
    if (decision.type() == DecisionType.ERROR) {
      throw ApprovalReleaseSteps.unavailable(decision);
    }
    if (decision.type() == DecisionType.DENY) {
      steps.denyStage2(approval, request, result.plan(), decision, actor);
    } else {
      steps.authorizePlan(approval, request, result.plan(), decision, actor);
    }
  }

  public void rejectAfterApproval(ApprovalRequest approval, ManifestRequest request, String actor, String comment) {
    steps.rejectAfterVerdict(approval, request, actor, comment);
  }
}

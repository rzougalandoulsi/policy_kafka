package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Stage 2, after quorum: marks the approval APPROVED, checks the plan is still the request's plan and still fresh, then
 * re-evaluates it with OPA under the team's quota lock. Sets {@code revalidationResult} (ALLOW | DENY | STALE); throws
 * on an OPA error so the approver transaction rolls back.
 */
@Component("revalidatePlanDelegate")
public class RevalidatePlanDelegate implements JavaDelegate {

  private final ApprovalReleaseSteps steps;
  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ShadowOutcomeRecorder shadow;

  public RevalidatePlanDelegate(ApprovalReleaseSteps steps, ApprovalRepository approvals, ManifestRequestRepository requests, ShadowOutcomeRecorder shadow) {
    this.steps = steps;
    this.approvals = approvals;
    this.requests = requests;
    this.shadow = shadow;
  }

  @Override
  public void execute(DelegateExecution execution) {
    if (!ApprovalProcess.authoritative(execution)) {
      shadow.record(execution, ApprovalProcess.OUTCOME_APPROVED);
      execution.setVariable(ApprovalProcess.REVALIDATION_RESULT, ApprovalProcess.RESULT_SHADOW);
      return;
    }
    String approvalId = ApprovalProcess.approvalId(execution);
    ApprovalRequest approval = approvals.lockById(approvalId).orElseThrow(() -> new IllegalStateException("Approval " + approvalId + " vanished"));
    ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();
    approval = steps.resolveApproval(approval, ApprovalStatus.APPROVED, "Quorum reached");
    ApprovalReleaseSteps.Stage2Revalidation result = steps.revalidateStage2(approval, request);
    if (result.stale()) {
      execution.setVariable(ApprovalProcess.REVALIDATION_RESULT, ApprovalProcess.RESULT_STALE);
      execution.setVariable(ApprovalProcess.STALE_STATE_VERSION, result.latestStateVersion());
      return;
    }
    if (result.decision().type() == DecisionType.ERROR) {
      throw ApprovalReleaseSteps.unavailable(result.decision());
    }
    execution.setVariable(ApprovalProcess.REVALIDATION_RESULT, result.decision().type() == DecisionType.DENY ? "DENY" : "ALLOW");
    execution.setVariable(ApprovalProcess.REVALIDATION_DECISION_ID, result.decision().id());
  }
}

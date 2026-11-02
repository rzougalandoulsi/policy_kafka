package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Every negative end of an approval: a REJECT verdict (approval REJECTED, request STAGE1_DENIED / PLAN_REJECTED,
 * REJECT_PLAN APPROVAL_REJECTED), a DENY on re-evaluation (POLICY_DENIED with the blocking violations) or a stale plan
 * (STALE_PLAN). Which one is derived from the process variables, so one bean serves the three service tasks.
 */
@Component("rejectApprovalDelegate")
public class RejectApprovalDelegate implements JavaDelegate {

  private final ApprovalReleaseSteps steps;
  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ProposedPlanRepository plans;
  private final ShadowOutcomeRecorder shadow;

  public RejectApprovalDelegate(ApprovalReleaseSteps steps, ApprovalRepository approvals, ManifestRequestRepository requests,
                                ProposedPlanRepository plans, ShadowOutcomeRecorder shadow) {
    this.steps = steps;
    this.approvals = approvals;
    this.requests = requests;
    this.plans = plans;
    this.shadow = shadow;
  }

  @Override
  public void execute(DelegateExecution execution) {
    String outcome = ApprovalProcess.string(execution, ApprovalProcess.OUTCOME);
    if (!ApprovalProcess.authoritative(execution)) {
      shadow.record(execution, outcome);
      return;
    }
    String approvalId = ApprovalProcess.approvalId(execution);
    String approverId = ApprovalProcess.string(execution, ApprovalProcess.APPROVER_ID);
    ApprovalRequest approval = approvals.lockById(approvalId).orElseThrow(() -> new IllegalStateException("Approval " + approvalId + " vanished"));
    ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();

    if (ApprovalProcess.OUTCOME_REJECTED.equals(outcome)) {
      approval = steps.resolveApproval(approval, ApprovalStatus.REJECTED, "Rejected by " + approverId);
      steps.rejectAfterVerdict(approval, request, approverId, ApprovalProcess.string(execution, ApprovalProcess.DECISION_COMMENT));
      return;
    }
    // APPROVED by the humans, refused on revalidation (the approval row is already APPROVED).
    String result = ApprovalProcess.string(execution, ApprovalProcess.REVALIDATION_RESULT);
    if (ApprovalProcess.RESULT_STALE.equals(result)) {
      ProposedPlan plan = plans.findByPlanId(approval.planId()).orElseThrow();
      Object latest = execution.getVariable(ApprovalProcess.STALE_STATE_VERSION);
      steps.stalePlan(approval, request, plan, ((Number) latest).longValue(), approverId);
      return;
    }
    var decision = steps.decision(ApprovalProcess.string(execution, ApprovalProcess.REVALIDATION_DECISION_ID));
    if (approval.stage() == GovernanceStage.REQUEST) {
      steps.denyStage1(approval, request, decision, approverId);
    } else {
      steps.denyStage2(approval, request, plans.findByPlanId(approval.planId()).orElseThrow(), decision, approverId);
    }
  }
}

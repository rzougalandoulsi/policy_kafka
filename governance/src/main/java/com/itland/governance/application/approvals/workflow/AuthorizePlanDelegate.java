package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Stage 2 release: PLAN_AUTHORIZED, quota reservation and AUTHORIZE_PLAN (with the revalidation decision id) in the outbox. */
@Component("authorizePlanDelegate")
public class AuthorizePlanDelegate implements JavaDelegate {

  private final ApprovalReleaseSteps steps;
  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ProposedPlanRepository plans;

  public AuthorizePlanDelegate(ApprovalReleaseSteps steps, ApprovalRepository approvals, ManifestRequestRepository requests, ProposedPlanRepository plans) {
    this.steps = steps;
    this.approvals = approvals;
    this.requests = requests;
    this.plans = plans;
  }

  @Override
  public void execute(DelegateExecution execution) {
    ApprovalRequest approval = approvals.findById(ApprovalProcess.approvalId(execution)).orElseThrow();
    ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();
    ProposedPlan plan = plans.findByPlanId(approval.planId()).orElseThrow();
    steps.authorizePlan(approval, request, plan, steps.decision(ApprovalProcess.string(execution, ApprovalProcess.REVALIDATION_DECISION_ID)),
        ApprovalProcess.string(execution, ApprovalProcess.APPROVER_ID));
  }
}

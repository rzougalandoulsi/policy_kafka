package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.infrastructure.persistence.ProposedPlanRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Stage 2 entry: the plan the approval refers to must be stored with the digest recorded in the process. Fails the
 * start (and the inbox transaction that triggered it) rather than collecting verdicts on a plan Governance cannot see.
 */
@Component("bindPlanDelegate")
public class BindPlanDelegate implements JavaDelegate {

  private final ProposedPlanRepository plans;

  public BindPlanDelegate(ProposedPlanRepository plans) {
    this.plans = plans;
  }

  @Override
  public void execute(DelegateExecution execution) {
    String planId = ApprovalProcess.string(execution, ApprovalProcess.PLAN_ID);
    String planDigest = ApprovalProcess.string(execution, ApprovalProcess.PLAN_DIGEST);
    ProposedPlan plan = plans.findByPlanId(planId).orElseThrow(() -> new IllegalStateException("Plan " + planId + " is not stored"));
    if (!plan.planDigest().equals(planDigest)) {
      throw new IllegalStateException("Plan " + planId + " digest " + plan.planDigest() + " differs from the approval's " + planDigest);
    }
  }
}

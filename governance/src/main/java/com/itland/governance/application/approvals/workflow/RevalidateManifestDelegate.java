package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.decision.DecisionType;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Stage 1, after quorum: marks the approval APPROVED and re-evaluates the manifest with OPA. Sets
 * {@code revalidationResult} (ALLOW | DENY) and {@code revalidationDecisionId}; throws on an OPA error so the whole
 * approver transaction, verdict included, rolls back.
 */
@Component("revalidateManifestDelegate")
public class RevalidateManifestDelegate implements JavaDelegate {

  private final ApprovalReleaseSteps steps;
  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ShadowOutcomeRecorder shadow;

  public RevalidateManifestDelegate(ApprovalReleaseSteps steps, ApprovalRepository approvals, ManifestRequestRepository requests, ShadowOutcomeRecorder shadow) {
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
    GovernanceDecision decision = steps.revalidateStage1(approval, request);
    if (decision.type() == DecisionType.ERROR) {
      throw ApprovalReleaseSteps.unavailable(decision);
    }
    execution.setVariable(ApprovalProcess.REVALIDATION_RESULT, decision.type() == DecisionType.DENY ? "DENY" : "ALLOW");
    execution.setVariable(ApprovalProcess.REVALIDATION_DECISION_ID, decision.id());
  }
}

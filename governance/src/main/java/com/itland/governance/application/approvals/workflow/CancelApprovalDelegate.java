package com.itland.governance.application.approvals.workflow;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The request ended by other means (Orchestrator completion, failure or rejection) while the approval was open: the
 * approval is CANCELLED and audited; nothing is published because the request is already closed.
 */
@Component("cancelApprovalDelegate")
public class CancelApprovalDelegate implements JavaDelegate {

  private final ApprovalReleaseSteps steps;
  private final ApprovalRepository approvals;
  private final AuditService audit;
  private final ShadowOutcomeRecorder shadow;

  public CancelApprovalDelegate(ApprovalReleaseSteps steps, ApprovalRepository approvals, AuditService audit, ShadowOutcomeRecorder shadow) {
    this.steps = steps;
    this.approvals = approvals;
    this.audit = audit;
    this.shadow = shadow;
  }

  @Override
  public void execute(DelegateExecution execution) {
    if (!ApprovalProcess.authoritative(execution)) {
      shadow.record(execution, ApprovalProcess.OUTCOME_CANCELLED);
      return;
    }
    String approvalId = ApprovalProcess.approvalId(execution);
    String reason = ApprovalProcess.string(execution, ApprovalProcess.CANCELLATION_REASON);
    ApprovalRequest approval = approvals.lockById(approvalId).orElseThrow(() -> new IllegalStateException("Approval " + approvalId + " vanished"));
    if (approval.status() != ApprovalStatus.PENDING) {
      return;
    }
    steps.resolveApproval(approval, ApprovalStatus.CANCELLED, reason == null ? "Request ended" : reason);
    audit.record("system", "APPROVAL_CANCELLED", "approval_request", approvalId, approval.requestId(),
        Map.of("reason", reason == null ? "" : reason, "processInstanceId", execution.getProcessInstanceId()));
  }
}

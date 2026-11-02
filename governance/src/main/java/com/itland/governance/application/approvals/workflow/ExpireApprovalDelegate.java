package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.infrastructure.observability.Mdc;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deadline boundary timer fired (async executor thread, its own transaction): approval EXPIRED, request
 * APPROVAL_EXPIRED, REJECT_PLAN for stage 2, reservations released. A decision that landed concurrently wins: the row
 * lock serialises the two and an approval that is no longer PENDING is left alone.
 */
@Component("expireApprovalDelegate")
public class ExpireApprovalDelegate implements JavaDelegate {

  private static final Logger log = LoggerFactory.getLogger(ExpireApprovalDelegate.class);

  private final ApprovalReleaseSteps steps;
  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ShadowOutcomeRecorder shadow;

  public ExpireApprovalDelegate(ApprovalReleaseSteps steps, ApprovalRepository approvals, ManifestRequestRepository requests, ShadowOutcomeRecorder shadow) {
    this.steps = steps;
    this.approvals = approvals;
    this.requests = requests;
    this.shadow = shadow;
  }

  @Override
  public void execute(DelegateExecution execution) {
    String approvalId = ApprovalProcess.approvalId(execution);
    try (Mdc ignored = Mdc.of(ApprovalProcess.string(execution, ApprovalProcess.REQUEST_ID), ApprovalProcess.string(execution, ApprovalProcess.PLAN_ID),
        approvalId, execution.getProcessInstanceId())) {
      if (!ApprovalProcess.authoritative(execution)) {
        shadow.record(execution, ApprovalProcess.OUTCOME_EXPIRED);
        return;
      }
      ApprovalRequest approval = approvals.lockById(approvalId).orElseThrow(() -> new IllegalStateException("Approval " + approvalId + " vanished"));
      if (approval.status() != ApprovalStatus.PENDING) {
        log.info("Deadline of approval {} fired after it was resolved as {}; nothing to do", approvalId, approval.status());
        return;
      }
      approval = steps.resolveApproval(approval, ApprovalStatus.EXPIRED, "Expired at " + approval.expiresAt());
      ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();
      steps.expire(approval, request);
    }
  }
}

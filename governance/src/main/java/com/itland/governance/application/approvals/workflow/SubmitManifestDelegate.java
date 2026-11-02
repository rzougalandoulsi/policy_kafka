package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Stage 1 release: request SUBMITTED and SUBMIT_MANIFEST written to the transactional outbox. */
@Component("submitManifestDelegate")
public class SubmitManifestDelegate implements JavaDelegate {

  private final ApprovalReleaseSteps steps;
  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;

  public SubmitManifestDelegate(ApprovalReleaseSteps steps, ApprovalRepository approvals, ManifestRequestRepository requests) {
    this.steps = steps;
    this.approvals = approvals;
    this.requests = requests;
  }

  @Override
  public void execute(DelegateExecution execution) {
    ApprovalRequest approval = approvals.findById(ApprovalProcess.approvalId(execution)).orElseThrow();
    ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();
    steps.submitManifest(approval, request, steps.decision(ApprovalProcess.string(execution, ApprovalProcess.REVALIDATION_DECISION_ID)),
        ApprovalProcess.string(execution, ApprovalProcess.APPROVER_ID));
  }
}

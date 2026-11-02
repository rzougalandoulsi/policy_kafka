package com.itland.governance.application.approvals.workflow;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.infrastructure.observability.GovernanceMetrics;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Shadow mode ({@code governance.workflow.engine=shadow}): the legacy implementation stays authoritative and the
 * process instance mirrors it. When the process reaches an outcome, the outcome is compared with the status the legacy
 * code gave the same approval and recorded (audit {@code WORKFLOW_SHADOW_OUTCOME}, metric
 * {@code governance.workflow.shadow.outcomes}). Nothing is transitioned, reserved or published from here.
 */
@Component
public class ShadowOutcomeRecorder {

  private static final Logger log = LoggerFactory.getLogger(ShadowOutcomeRecorder.class);

  private final ApprovalRepository approvals;
  private final AuditService audit;
  private final GovernanceMetrics metrics;

  public ShadowOutcomeRecorder(ApprovalRepository approvals, AuditService audit, GovernanceMetrics metrics) {
    this.approvals = approvals;
    this.audit = audit;
    this.metrics = metrics;
  }

  public void record(DelegateExecution execution, String engineOutcome) {
    String approvalId = ApprovalProcess.approvalId(execution);
    ApprovalRequest approval = approvals.findById(approvalId).orElse(null);
    String legacyStatus = approval == null ? "MISSING" : approval.status().name();
    boolean match = legacyStatus.equals(engineOutcome);
    String stage = ApprovalProcess.string(execution, ApprovalProcess.STAGE);
    metrics.recordShadowOutcome(stage, engineOutcome, match);
    audit.record("system", "WORKFLOW_SHADOW_OUTCOME", "approval_request", approvalId, approval == null ? null : approval.requestId(),
        Map.of("engineOutcome", engineOutcome, "legacyStatus", legacyStatus, "match", match,
            "processInstanceId", execution.getProcessInstanceId(), "stage", stage == null ? "" : stage));
    if (match) {
      log.info("Shadow workflow for approval {} agrees with legacy: {}", approvalId, engineOutcome);
    } else {
      log.warn("Shadow workflow for approval {} reached {} while legacy status is {}", approvalId, engineOutcome, legacyStatus);
    }
  }
}

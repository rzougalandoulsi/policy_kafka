package com.itland.governance.application.approvals.workflow;

import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.request.ManifestRequest;
import org.flowable.engine.delegate.DelegateExecution;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract between Governance and the BPMN definitions under {@code classpath:/processes}: definition keys, the
 * verdict task, the cancellation message and the process variables. Variables are small immutable identifiers and
 * primitives only (never a manifest or an action list); their names are append-only across BPMN versions.
 */
public final class ApprovalProcess {

  public static final String MANIFEST_APPROVAL = "governance-manifest-approval";
  public static final String ACTION_PLAN_APPROVAL = "governance-action-plan-approval";
  public static final String APPROVAL_DECISION = "governance-approval-decision";
  public static final List<String> DEFINITION_KEYS = List.of(MANIFEST_APPROVAL, ACTION_PLAN_APPROVAL, APPROVAL_DECISION);

  public static final String VERDICT_TASK = "collectVerdict";
  public static final String CANCEL_MESSAGE = "approvalCancelled";

  public static final String APPROVAL_ID = "approvalId";
  public static final String REQUEST_ID = "requestId";
  public static final String TEAM_ID = "teamId";
  public static final String REPOSITORY_ID = "repositoryId";
  public static final String ENVIRONMENT = "environment";
  public static final String STAGE = "governanceStage";
  public static final String DECISION_ID = "decisionId";
  public static final String POLICY_REVISION = "policyRevision";
  public static final String DIGEST = "digest";
  public static final String PLAN_ID = "planId";
  public static final String PLAN_DIGEST = "planDigest";
  public static final String APPROVAL_POLICY_ID = "approvalPolicyId";
  public static final String REQUIRED_APPROVALS = "requiredApprovals";
  public static final String APPROVER_ROLES = "approverRoles";
  public static final String SEPARATION_OF_DUTIES = "separationOfDuties";
  public static final String REQUESTER_ID = "requesterId";
  public static final String DEADLINE = "approvalDeadline";
  public static final String APPROVED_COUNT = "approvedCount";
  /** false in shadow mode: the process records what it would have done and releases nothing. */
  public static final String AUTHORITATIVE = "authoritative";
  // Set when the verdict task is completed.
  public static final String VERDICT = "verdict";
  public static final String APPROVER_ID = "approverId";
  public static final String DECISION_COMMENT = "decisionComment";
  // Set by the shared sub-process and the delegates.
  public static final String OUTCOME = "approvalOutcome";
  public static final String REVALIDATION_RESULT = "revalidationResult";
  public static final String REVALIDATION_DECISION_ID = "revalidationDecisionId";
  public static final String STALE_STATE_VERSION = "staleStateVersion";
  public static final String CANCELLATION_REASON = "cancellationReason";

  public static final String OUTCOME_APPROVED = "APPROVED";
  public static final String OUTCOME_REJECTED = "REJECTED";
  public static final String OUTCOME_EXPIRED = "EXPIRED";
  public static final String OUTCOME_CANCELLED = "CANCELLED";
  public static final String RESULT_STALE = "STALE";
  public static final String RESULT_SHADOW = "SHADOW";

  private ApprovalProcess() {}

  public static String definitionKey(GovernanceStage stage) {
    return stage == GovernanceStage.REQUEST ? MANIFEST_APPROVAL : ACTION_PLAN_APPROVAL;
  }

  public static Map<String, Object> startVariables(ApprovalRequest approval, ManifestRequest request, GovernanceDecision decision,
                                                   String approvalPolicyId, boolean authoritative) {
    Map<String, Object> v = new HashMap<>();
    v.put(APPROVAL_ID, approval.id());
    v.put(REQUEST_ID, request.id());
    v.put(TEAM_ID, request.teamId());
    v.put(REPOSITORY_ID, request.repositoryId());
    v.put(ENVIRONMENT, request.environment());
    v.put(STAGE, approval.stage().name());
    v.put(DECISION_ID, decision.id());
    v.put(POLICY_REVISION, decision.policyRevision());
    v.put(DIGEST, approval.digest());
    v.put(PLAN_ID, approval.planId());
    v.put(PLAN_DIGEST, approval.planDigest());
    v.put(APPROVAL_POLICY_ID, approvalPolicyId);
    v.put(REQUIRED_APPROVALS, approval.requiredApprovals());
    v.put(APPROVER_ROLES, String.join(",", approval.approverRoles()));
    v.put(SEPARATION_OF_DUTIES, approval.separationOfDuties());
    v.put(REQUESTER_ID, approval.requesterId());
    v.put(DEADLINE, Date.from(approval.expiresAt()));
    v.put(APPROVED_COUNT, 0);
    v.put(AUTHORITATIVE, authoritative);
    return v;
  }

  // ---- typed access from delegates ----

  public static String approvalId(DelegateExecution execution) {
    return string(execution, APPROVAL_ID);
  }

  public static GovernanceStage stage(DelegateExecution execution) {
    return GovernanceStage.valueOf(string(execution, STAGE));
  }

  public static boolean authoritative(DelegateExecution execution) {
    Object v = execution.getVariable(AUTHORITATIVE);
    return !(v instanceof Boolean b) || b;
  }

  public static String string(DelegateExecution execution, String name) {
    Object v = execution.getVariable(name);
    return v == null ? null : v.toString();
  }
}

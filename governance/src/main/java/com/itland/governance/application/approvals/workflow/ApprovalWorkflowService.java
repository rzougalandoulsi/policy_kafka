package com.itland.governance.application.approvals.workflow;

import com.itland.governance.application.approvals.ApprovalService;
import com.itland.governance.application.approvals.ApprovalService.ApprovalRuleException;
import com.itland.governance.application.approvals.ApprovalService.Detail;
import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.policy.NotFoundException;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.configuration.GovernanceProperties.Workflow.Engine;
import com.itland.governance.domain.approval.ApprovalDecision;
import com.itland.governance.domain.approval.ApprovalEngine;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalRules;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.approval.ApprovalVerdict;
import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.GovernanceDecision;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.policy.ApprovalConfiguration;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.infrastructure.observability.Mdc;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.persistence.ManifestRequestRepository;
import com.itland.governance.infrastructure.security.CallerContext;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Human approvals on the embedded Flowable engine (ADR 0011). Governance keeps {@code approval_request} as the public
 * record and correlation key (the approval id is the process business key); the engine owns the task lifecycle, the
 * deadline timer, the quorum loop, cancellation and history. Authorisation never comes from task assignment: every
 * verdict is checked against the authenticated principal, the approver roles and separation of duties under the
 * approval's row lock before the task is completed. Completion runs the release service tasks synchronously in the
 * same transaction, so the HTTP response carries the released request status and an OPA failure rolls everything
 * back. Rows opened by the legacy implementation ({@code engine = LEGACY}) keep resolving through it until drained.
 */
@Service
public class ApprovalWorkflowService {

  private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflowService.class);

  private final ApprovalRepository approvals;
  private final ManifestRequestRepository requests;
  private final ApprovalService legacy;
  private final TeamAuthorization authorization;
  private final AuditService audit;
  private final RuntimeService runtime;
  private final TaskService tasks;
  private final Engine mode;
  private final Blocking blocking;
  private final Clock clock;

  public ApprovalWorkflowService(ApprovalRepository approvals, ManifestRequestRepository requests, ApprovalService legacy,
                                 TeamAuthorization authorization, AuditService audit, RuntimeService runtime, TaskService tasks,
                                 GovernanceProperties properties, Blocking blocking, Clock clock) {
    this.approvals = approvals;
    this.requests = requests;
    this.legacy = legacy;
    this.authorization = authorization;
    this.audit = audit;
    this.runtime = runtime;
    this.tasks = tasks;
    this.mode = properties.workflow().engine();
    this.blocking = blocking;
    this.clock = clock;
  }

  public Engine mode() {
    return mode;
  }

  /**
   * Opens a PENDING approval and, unless the engine mode is {@code legacy}, starts the matching process instance in
   * the caller's transaction. Idempotent per request and stage: a replayed trigger returns the open approval.
   */
  public ApprovalRequest open(ManifestRequest request, GovernanceDecision decision, GovernanceStage stage, List<ApprovalRequirement> requirements,
                              String digest, String planId, String planDigest) {
    Optional<ApprovalRequest> open = openApproval(request.id(), stage);
    if (open.isPresent()) {
      log.info("Approval {} already open for request {} stage {}; not opening a second one", open.get().id(), request.id(), stage);
      return open.get();
    }
    ApprovalConfiguration config = approvals.findActiveConfiguration().orElseThrow(() -> new IllegalStateException("No ACTIVE approval configuration"));
    Instant now = clock.instant();
    ApprovalEngine engine = mode == Engine.FLOWABLE ? ApprovalEngine.FLOWABLE : ApprovalEngine.LEGACY;
    ApprovalRequest approval = new ApprovalRequest("apr-" + UUID.randomUUID(), request.id(), stage, decision.id(), planId, planDigest, digest,
        ApprovalStatus.PENDING, request.principalId(), config.requiredApprovals(), config.approverRoles(), config.separationOfDuties(),
        requirements, now.plus(Duration.ofHours(config.ttlHours())), 0, now, null, null, engine, null);
    try {
      approvals.insert(approval);
    } catch (DuplicateKeyException race) {
      return openApproval(request.id(), stage).orElseThrow(() -> race);
    }
    Map<String, Object> details = new HashMap<>(Map.of("stage", stage.name(), "decisionId", decision.id(), "requiredApprovals", config.requiredApprovals(),
        "expiresAt", approval.expiresAt().toString(), "engine", engine.name(), "approvalPolicyId", config.id()));
    if (mode != Engine.LEGACY) {
      ProcessInstance instance = runtime.createProcessInstanceBuilder()
          .processDefinitionKey(ApprovalProcess.definitionKey(stage))
          .businessKey(approval.id())
          .name("Approval " + approval.id() + " of request " + request.id())
          .variables(ApprovalProcess.startVariables(approval, request, decision, config.id(), engine == ApprovalEngine.FLOWABLE))
          .start();
      approvals.bindProcess(approval.id(), instance.getId());
      approval = approvals.findById(approval.id()).orElseThrow();
      details.put("processInstanceId", instance.getId());
      log.info("Started {} process {} for approval {} (engine {})", ApprovalProcess.definitionKey(stage), instance.getId(), approval.id(), engine);
    }
    audit.record("system", "APPROVAL_OPENED", "approval_request", approval.id(), request.id(), details);
    return approval;
  }

  private Optional<ApprovalRequest> openApproval(String requestId, GovernanceStage stage) {
    return approvals.findPendingByRequest(requestId).stream().filter(a -> a.stage() == stage).findFirst();
  }

  public Mono<List<ApprovalRequest>> list(CallerContext caller, ApprovalStatus status, String teamId, int limit) {
    return legacy.list(caller, status, teamId, limit);
  }

  public Mono<Detail> get(CallerContext caller, String approvalId) {
    return legacy.get(caller, approvalId);
  }

  /**
   * Records a verdict (idempotent per approver) and lets the owning implementation resolve the approval: the legacy
   * quorum for LEGACY rows (mirrored into the shadow process when one exists), the Flowable verdict task otherwise.
   */
  public Mono<Detail> decide(CallerContext approver, String approvalId, ApprovalVerdict verdict, String comment) {
    authorization.requireApprover(approver);
    return blocking.inTransaction(() -> decideNow(approver, approvalId, verdict, comment));
  }

  Detail decideNow(CallerContext approver, String approvalId, ApprovalVerdict verdict, String comment) {
    ApprovalRequest approval = approvals.lockById(approvalId).orElseThrow(() -> new NotFoundException("Approval " + approvalId + " not found"));
    ManifestRequest request = requests.findById(approval.requestId()).orElseThrow();
    try (Mdc ignored = Mdc.of(request.id(), approval.planId(), approvalId, approval.processInstanceId())) {
      Instant now = clock.instant();
      List<ApprovalDecision> existing = approvals.decisions(approvalId);
      Optional<ApprovalDecision> mine = existing.stream().filter(d -> d.approverId().equals(approver.principalId())).findFirst();
      if (mine.isPresent()) {
        if (mine.get().verdict() != verdict) {
          throw new ApprovalRuleException("You already recorded " + mine.get().verdict() + " on this approval");
        }
        return new Detail(approval, existing, request); // idempotent replay
      }
      ApprovalRules.eligibility(approval, approver, existing, now).ifPresent(reason -> { throw new ApprovalRuleException(reason); });

      ApprovalDecision decision = new ApprovalDecision("apd-" + UUID.randomUUID(), approvalId, approver.principalId(), verdict, comment, now);
      approvals.insertDecision(decision);
      List<ApprovalDecision> all = approvals.decisions(approvalId);
      audit.record(approver.principalId(), "APPROVAL_DECISION", "approval_request", approvalId, request.id(),
          Map.of("verdict", verdict.name(), "comment", comment == null ? "" : comment, "engine", approval.engine().name()));
      int approvedCount = (int) all.stream().filter(d -> d.verdict() == ApprovalVerdict.APPROVE).map(ApprovalDecision::approverId).distinct().count();

      if (approval.engine() == ApprovalEngine.LEGACY) {
        Detail detail = legacy.resolve(approval, request, all, approver, verdict, comment);
        if (approval.processInstanceId() != null) {
          completeVerdictTask(approval, verdict, approver.principalId(), comment, approvedCount); // shadow mirror, never releases
        }
        return detail;
      }
      completeVerdictTask(approval, verdict, approver.principalId(), comment, approvedCount);
      return new Detail(approvals.findById(approvalId).orElseThrow(), all, requests.findById(request.id()).orElseThrow());
    }
  }

  private void completeVerdictTask(ApprovalRequest approval, ApprovalVerdict verdict, String approverId, String comment, int approvedCount) {
    Task task = tasks.createTaskQuery().processInstanceBusinessKey(approval.id()).taskDefinitionKey(ApprovalProcess.VERDICT_TASK).singleResult();
    if (task == null) {
      throw new IllegalStateException("Approval " + approval.id() + " has no open verdict task in process " + approval.processInstanceId()
          + "; an operator must inspect the workflow (dead-letter jobs) before it can be decided");
    }
    Map<String, Object> variables = new HashMap<>();
    variables.put(ApprovalProcess.VERDICT, verdict.name());
    variables.put(ApprovalProcess.APPROVER_ID, approverId);
    variables.put(ApprovalProcess.APPROVED_COUNT, approvedCount);
    variables.put(ApprovalProcess.DECISION_COMMENT, comment == null ? "" : comment);
    try {
      tasks.complete(task.getId(), variables);
    } catch (FlowableException e) {
      // Delegates rethrow their own exceptions unchanged; anything the engine wraps is surfaced once, with its cause.
      for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
        if (t instanceof ApprovalRuleException rule) throw rule;
        if (t instanceof IllegalStateException state) throw state;
      }
      throw e;
    }
  }

  /**
   * The request ended by other means: every open approval of the request is cancelled. Flowable approvals receive the
   * {@code approvalCancelled} message (the cancel service task resolves the row); legacy rows are resolved directly. A
   * row whose process is no longer waiting (for example a dead-lettered job) is resolved here so nothing is abandoned.
   */
  public void cancel(String requestId, String reason) {
    for (ApprovalRequest a : approvals.findPendingByRequest(requestId)) {
      if (a.engine() == ApprovalEngine.LEGACY) {
        legacy.cancelOne(a, reason); // authoritative first, so a shadow process compares against the final status
      }
      if (a.processInstanceId() != null) {
        Execution waiting = runtime.createExecutionQuery().processInstanceId(a.processInstanceId())
            .messageEventSubscriptionName(ApprovalProcess.CANCEL_MESSAGE).singleResult();
        if (waiting != null) {
          runtime.messageEventReceived(ApprovalProcess.CANCEL_MESSAGE, waiting.getId(), Map.of(ApprovalProcess.CANCELLATION_REASON, reason));
        } else {
          log.warn("Process {} of approval {} is not waiting for cancellation; resolving the approval directly", a.processInstanceId(), a.id());
        }
      }
      ApprovalRequest fresh = approvals.findById(a.id()).orElseThrow();
      if (fresh.status() == ApprovalStatus.PENDING) {
        legacy.cancelOne(fresh, reason);
      }
    }
  }
}

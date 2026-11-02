package com.itland.governance.api.approvals;

import com.itland.governance.api.Callers;
import com.itland.governance.application.approvals.ApprovalService;
import com.itland.governance.application.approvals.workflow.ApprovalWorkflowService;
import com.itland.governance.domain.approval.ApprovalDecision;
import com.itland.governance.domain.approval.ApprovalRequest;
import com.itland.governance.domain.approval.ApprovalStatus;
import com.itland.governance.domain.approval.ApprovalVerdict;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "Human approval workflow bound to immutable plan digests (embedded BPMN engine; approval ids are the public contract)")
public class ApprovalController {

  public record DecisionBody(@NotNull ApprovalVerdict verdict, @Size(max = 2000) String comment) {}

  public record ApprovalDetail(ApprovalRequest approval, List<ApprovalDecision> decisions, String requestStatus, String teamId,
                               String repositoryId, String environment) {}

  private final ApprovalWorkflowService approvals;
  private final Callers callers;

  public ApprovalController(ApprovalWorkflowService approvals, Callers callers) {
    this.approvals = approvals;
    this.callers = callers;
  }

  @GetMapping
  @Operation(summary = "List approvals", description = "Approvers/auditors see all; team members must pass teamId of one of their teams.")
  public Mono<List<ApprovalRequest>> list(Authentication auth, @RequestParam(required = false) ApprovalStatus status,
                                          @RequestParam(required = false) String teamId, @RequestParam(defaultValue = "100") int limit) {
    return approvals.list(callers.of(auth), status, teamId, limit);
  }

  @GetMapping("/{approvalId}")
  @Operation(summary = "Approval details with recorded verdicts")
  public Mono<ApprovalDetail> get(Authentication auth, @PathVariable String approvalId) {
    return approvals.get(callers.of(auth), approvalId).map(ApprovalController::detail);
  }

  @PostMapping("/{approvalId}/decisions")
  @Operation(summary = "Record an APPROVE or REJECT verdict", description = "Idempotent per approver; quorum triggers revalidation and release.")
  public Mono<ApprovalDetail> decide(Authentication auth, @PathVariable String approvalId, @Valid @RequestBody DecisionBody body) {
    return approvals.decide(callers.of(auth), approvalId, body.verdict(), body.comment()).map(ApprovalController::detail);
  }

  private static ApprovalDetail detail(ApprovalService.Detail d) {
    return new ApprovalDetail(d.approval(), d.decisions(), d.request().status().name(), d.request().teamId(), d.request().repositoryId(), d.request().environment());
  }
}

package com.itland.governance.api.manifest;

import com.itland.governance.api.Callers;
import com.itland.governance.application.submission.ManifestSubmissionService;
import com.itland.governance.domain.request.RequestStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/manifests")
@Tag(name = "Manifests", description = "Submit complete desired-state manifests")
public class ManifestController {

  private final ManifestSubmissionService submission;
  private final Callers callers;

  public ManifestController(ManifestSubmissionService submission, Callers callers) {
    this.submission = submission;
    this.callers = callers;
  }

  @PostMapping
  @Operation(summary = "Submit a manifest", description = "Stage 1 governance. 202 when accepted or pending approval, 422 when denied by policy, 409 on Idempotency-Key reuse with a different manifest, 503 when the policy engine is unavailable (fail closed).")
  public Mono<ResponseEntity<Object>> submit(Authentication authentication,
                                             @Parameter(description = "Client-chosen key making retries safe") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @Valid @RequestBody ManifestDtos.ManifestRequestBody body) {
    return submission.submit(callers.of(authentication), body.toDomain(), idempotencyKey)
        .map(outcome -> {
          var r = outcome.request();
          String statusUrl = "/api/v1/requests/" + r.id();
          String decisionId = outcome.decision() == null ? null : outcome.decision().id();
          if (r.status() == RequestStatus.STAGE1_DENIED) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).header("Location", statusUrl).body((Object) new ManifestDtos.RejectedResponse(
                r.id(), "REJECTED", decisionId, outcome.decision() == null ? null : outcome.decision().policyRevision(),
                outcome.decision() == null ? java.util.List.of() : outcome.decision().violations(),
                outcome.decision() == null ? java.util.List.of() : outcome.decision().approvalRequirements()));
          }
          String status = switch (r.status()) {
            case PENDING_APPROVAL_STAGE1, PENDING_APPROVAL_STAGE2 -> "PENDING_APPROVAL";
            case RECEIVED, SUBMITTED, PLAN_PROPOSED, PLAN_AUTHORIZED -> "ACCEPTED";
            default -> r.status().name();
          };
          return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED).header("Location", statusUrl)
              .body((Object) new ManifestDtos.AcceptedResponse(r.id(), status, statusUrl, decisionId));
        });
  }
}

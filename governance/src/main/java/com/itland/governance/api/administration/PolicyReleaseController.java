package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.application.policy.PolicyBundleService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.policy.PolicyRelease;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The policy release: the tested set of one ACTIVE version per bundle. Activating a release switches all bundles in one
 * transaction; {@code current} compares the expected set with what OPA actually runs (same data as the readiness probe).
 */
@RestController
@RequestMapping("/api/v1/admin/policy-releases")
@Tag(name = "Administration: policy releases", description = "Expected versus active bundle revisions; atomic activation of a full set")
public class PolicyReleaseController {

  public record ActivateReleaseBody(@NotEmpty List<String> versionIds) {}

  private final PolicyBundleService service;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;

  public PolicyReleaseController(PolicyBundleService service, TeamAuthorization authorization, Callers callers, Blocking blocking) {
    this.service = service;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
  }

  @GetMapping("/current")
  @Operation(summary = "Expected release versus the revisions OPA reports, per bundle")
  public Mono<PolicyBundleService.RevisionDiagnostics> current(Authentication auth) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(service::diagnostics);
  }

  @PostMapping
  @Operation(summary = "Activate a complete release: one uploaded version id per bundle, switched atomically")
  public Mono<PolicyRelease> activate(Authentication auth, @RequestBody ActivateReleaseBody body) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.activateRelease(body.versionIds(), c.principalId()));
  }
}

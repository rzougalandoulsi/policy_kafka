package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.application.policy.PolicyBundleService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.policy.PolicyBundleVersion;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;

/**
 * Controlled bundle publishing. There is deliberately no endpoint that accepts raw Rego text: bundles are built and
 * tested by policies/build.sh (opa check/test/build) and uploaded here as an artifact with their test report.
 */
@RestController
@RequestMapping("/api/v1/admin/policy-bundles")
@Tag(name = "Administration: policy bundles", description = "Versioned Rego bundles served to OPA; activation and rollback")
public class PolicyBundleAdminController {

  public record UploadBody(@NotBlank String bundleBase64, @NotBlank String testReportJson, String comment) {}

  private final PolicyBundleService service;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;

  public PolicyBundleAdminController(PolicyBundleService service, TeamAuthorization authorization, Callers callers, Blocking blocking) {
    this.service = service;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
  }

  @GetMapping
  public Mono<List<PolicyBundleVersion>> list(Authentication auth) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(service::list);
  }

  @GetMapping("/{id}")
  public Mono<PolicyBundleVersion> get(Authentication auth, @PathVariable String id) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> service.get(id));
  }

  @GetMapping("/diagnostics")
  @Operation(summary = "Active revision versus the revision OPA reports")
  public Mono<PolicyBundleService.RevisionDiagnostics> diagnostics(Authentication auth) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(service::diagnostics);
  }

  @PostMapping
  @Operation(summary = "Upload a built bundle (tar.gz, base64) with its opa test JSON report")
  public Mono<PolicyBundleVersion> upload(Authentication auth, @RequestBody UploadBody body) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    byte[] bytes = Base64.getDecoder().decode(body.bundleBase64());
    return blocking.inTransaction(() -> service.upload(bytes, body.testReportJson(), body.comment(), c.principalId()));
  }

  @PostMapping("/{id}/activate")
  public Mono<PolicyBundleVersion> activate(Authentication auth, @PathVariable String id) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.activate(id, c.principalId()));
  }

  @PostMapping("/rollback")
  public Mono<PolicyBundleVersion> rollback(Authentication auth) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.rollback(c.principalId()));
  }
}

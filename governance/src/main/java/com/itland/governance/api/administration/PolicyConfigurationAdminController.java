package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.application.policy.PolicyConfigurationService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.policy.PolicyConfiguration;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/policy-configurations")
@Tag(name = "Administration: policy configuration", description = "Versioned runtime parameters supplied to OPA as trusted data")
public class PolicyConfigurationAdminController {

  public record CreateBody(@NotNull Map<String, Object> content, String comment) {}

  private final PolicyConfigurationService service;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;

  public PolicyConfigurationAdminController(PolicyConfigurationService service, TeamAuthorization authorization, Callers callers, Blocking blocking) {
    this.service = service;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
  }

  @GetMapping
  @Operation(summary = "List configuration versions")
  public Mono<List<PolicyConfiguration>> list(Authentication auth) {
    var c = callers.of(auth);
    authorization.requireAuditor(c);
    return blocking.call(service::list);
  }

  @GetMapping("/{id}")
  public Mono<PolicyConfiguration> get(Authentication auth, @PathVariable String id) {
    var c = callers.of(auth);
    authorization.requireAuditor(c);
    return blocking.call(() -> service.get(id));
  }

  @PostMapping
  @Operation(summary = "Create a draft configuration version")
  public Mono<PolicyConfiguration> create(Authentication auth, @RequestBody CreateBody body) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.createDraft(body.content(), body.comment(), c.principalId()));
  }

  @PostMapping("/{id}/activate")
  @Operation(summary = "Activate a version (retires the current one)")
  public Mono<PolicyConfiguration> activate(Authentication auth, @PathVariable String id) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.activate(id, c.principalId()));
  }

  @PostMapping("/rollback")
  @Operation(summary = "Re-activate the previously active version")
  public Mono<PolicyConfiguration> rollback(Authentication auth) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.rollback(c.principalId()));
  }
}

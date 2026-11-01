package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.application.exceptions.ExceptionAdminService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.exception.PolicyException;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/exceptions")
@Tag(name = "Administration: scoped exceptions", description = "Time-boxed, scoped exemptions from bypassable policies")
public class ExceptionAdminController {

  public record CreateBody(@NotBlank String policyId, String principalId, @NotBlank String teamId, String environment, String resourcePattern,
                           List<String> actionTypes, @NotNull Instant expiresAt, @NotBlank String reason) {}

  private final ExceptionAdminService service;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;

  public ExceptionAdminController(ExceptionAdminService service, TeamAuthorization authorization, Callers callers, Blocking blocking) {
    this.service = service;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
  }

  @GetMapping
  public Mono<List<PolicyException>> list(Authentication auth, @RequestParam(required = false) String teamId,
                                          @RequestParam(defaultValue = "false") boolean includeInactive) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> service.list(teamId, includeInactive));
  }

  @GetMapping("/{id}")
  public Mono<PolicyException> get(Authentication auth, @PathVariable String id) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> service.get(id));
  }

  @PostMapping
  @Operation(summary = "Create a scoped exception (admin only; clients can never supply their own)")
  public Mono<PolicyException> create(Authentication auth, @RequestBody CreateBody b) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.create(new ExceptionAdminService.CreateCommand(b.policyId(), b.principalId(), b.teamId(),
        b.environment(), b.resourcePattern(), b.actionTypes(), b.expiresAt(), b.reason()), c.principalId()));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Revoke an exception (idempotent)")
  public Mono<PolicyException> revoke(Authentication auth, @PathVariable String id, @RequestParam(required = false) String reason) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.revoke(id, reason, c.principalId()));
  }
}

package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.application.audit.AuditService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.policy.ApprovalConfiguration;
import com.itland.governance.infrastructure.persistence.ApprovalRepository;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/approval-configurations")
@Tag(name = "Administration: approvals", description = "Approver roles, quorum, separation of duties, expiry")
public class ApprovalConfigurationAdminController {

  public record CreateBody(@NotEmpty List<String> approverRoles, @Min(1) @Max(10) int requiredApprovals, boolean separationOfDuties,
                           @Min(1) @Max(720) int ttlHours) {}

  private final ApprovalRepository repository;
  private final AuditService audit;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;
  private final Clock clock;

  public ApprovalConfigurationAdminController(ApprovalRepository repository, AuditService audit, TeamAuthorization authorization, Callers callers,
                                              Blocking blocking, Clock clock) {
    this.repository = repository;
    this.audit = audit;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
    this.clock = clock;
  }

  @GetMapping
  public Mono<List<ApprovalConfiguration>> list(Authentication auth) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(repository::listConfigurations);
  }

  @PostMapping
  public Mono<ApprovalConfiguration> create(Authentication auth, @RequestBody CreateBody b) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    Map<String, Object> content = Map.of("approverRoles", b.approverRoles(), "requiredApprovals", b.requiredApprovals(),
        "separationOfDuties", b.separationOfDuties(), "ttlHours", b.ttlHours());
    return blocking.inTransaction(() -> {
      ApprovalConfiguration created = repository.insertConfiguration("apr-" + UUID.randomUUID(), content, c.principalId(), clock.instant());
      audit.record(c.principalId(), "APPROVAL_CONFIGURATION_CREATED", "approval_configuration", created.id(), content);
      return created;
    });
  }

  @PostMapping("/{id}/activate")
  public Mono<ApprovalConfiguration> activate(Authentication auth, @PathVariable String id) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> {
      repository.activateConfiguration(id, clock.instant());
      audit.record(c.principalId(), "APPROVAL_CONFIGURATION_ACTIVATED", "approval_configuration", id, Map.of());
      return repository.findActiveConfiguration().orElseThrow();
    });
  }
}

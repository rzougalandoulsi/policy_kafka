package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.application.teams.TeamConfigurationService;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.domain.team.TeamConfiguration;
import com.itland.governance.domain.team.TeamConfigurationChange;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Team configuration (input contract 2.0 {@code input.team}): authorised prefixes, quotas per resource type, allowed-role
 * override, environment parameters. Governance is the system of record; rows marked {@code source=external} are read-only
 * until an admin takes ownership back. Changes apply to the next evaluation, no restart involved.
 */
@RestController
@RequestMapping("/api/v1/admin/teams")
@Tag(name = "Administration: team configuration", description = "Authorised prefixes, quotas and role overrides per team; versioned and audited")
public class TeamConfigurationAdminController {

  public record UpsertBody(List<String> authorizedPrefixes, Map<String, Long> quotas, List<String> allowedRoles,
                           Map<String, Object> environments, String source, String sourceVersion, String comment) {}

  private final TeamConfigurationService service;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;

  public TeamConfigurationAdminController(TeamConfigurationService service, TeamAuthorization authorization, Callers callers, Blocking blocking) {
    this.service = service;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
  }

  @GetMapping
  @Operation(summary = "Teams with a stored configuration")
  public Mono<List<TeamConfiguration>> list(Authentication auth) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(service::list);
  }

  @GetMapping("/{teamId}")
  @Operation(summary = "Effective configuration of a team (source=default with version 0 when nothing is stored)")
  public Mono<TeamConfiguration> get(Authentication auth, @PathVariable String teamId) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> service.get(teamId));
  }

  @PutMapping("/{teamId}")
  @Operation(summary = "Create or replace a team's configuration (admin); bumps the version and records history and audit")
  public Mono<TeamConfiguration> upsert(Authentication auth, @PathVariable String teamId, @RequestBody UpsertBody b) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.upsert(teamId, new TeamConfigurationService.UpsertCommand(b.authorizedPrefixes(), b.quotas(),
        b.allowedRoles(), b.environments(), b.source(), b.sourceVersion(), b.comment()), c.principalId()));
  }

  @DeleteMapping("/{teamId}")
  @Operation(summary = "Remove a team's stored configuration; the team falls back to the defaults (returned)")
  public Mono<TeamConfiguration> delete(Authentication auth, @PathVariable String teamId) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> service.delete(teamId, c.principalId()));
  }

  @GetMapping("/{teamId}/history")
  @Operation(summary = "Versions of a team's configuration, newest first")
  public Mono<List<TeamConfigurationChange>> history(Authentication auth, @PathVariable String teamId) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> service.history(teamId));
  }

  @GetMapping("/{teamId}/usage")
  @Operation(summary = "Confirmed usage per repository and environment, plus open quota reservations")
  public Mono<TeamConfigurationService.TeamUsage> usage(Authentication auth, @PathVariable String teamId) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> service.usage(teamId));
  }
}

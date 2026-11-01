package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.infrastructure.opa.OpaClient;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Read-only catalogue of the policies the ACTIVE bundle can emit. The bundle itself is the source of truth
 * (policies/governance/catalog.rego): Governance only relays data.governance.catalog.policies from OPA so
 * operators (and the admin UI) can tell which policies are bypassable before scoping an exception.
 */
@RestController
@RequestMapping("/api/v1/admin/policies")
@Tag(name = "Administration: policy catalogue", description = "Policies declared by the active bundle: severity, bypassability, section")
public class PolicyCatalogController {

  private final OpaClient opa;
  private final TeamAuthorization authorization;
  private final Callers callers;

  public PolicyCatalogController(OpaClient opa, TeamAuthorization authorization, Callers callers) {
    this.opa = opa;
    this.authorization = authorization;
    this.callers = callers;
  }

  @GetMapping
  @Operation(summary = "Policy catalogue of the active bundle",
      description = "Each entry: policyId, kind (VIOLATION|APPROVAL), section, severity, bypassable, stages, resourceType, description. Empty when no bundle is loaded; 503 when OPA is unreachable.")
  public Mono<List<Map<String, Object>>> list(Authentication auth) {
    authorization.requireAuditor(callers.of(auth));
    return opa.catalog();
  }
}

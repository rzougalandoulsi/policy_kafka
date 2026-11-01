package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.infrastructure.persistence.ShadowFindingRepository;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/** What policies in shadow mode would have blocked or sent to approval: the evidence for flipping them to enforce. */
@RestController
@RequestMapping("/api/v1/admin/shadow-findings")
@Tag(name = "Administration: shadow findings", description = "Violations and approval requirements of policies running in shadow mode (never blocking)")
public class ShadowFindingController {

  private final ShadowFindingRepository findings;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;

  public ShadowFindingController(ShadowFindingRepository findings, TeamAuthorization authorization, Callers callers, Blocking blocking) {
    this.findings = findings;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
  }

  @GetMapping
  @Operation(summary = "Shadow findings, newest first")
  public Mono<List<ShadowFindingRepository.ShadowFinding>> list(Authentication auth,
                                                                @RequestParam(required = false) String policyId,
                                                                @RequestParam(required = false) String teamId,
                                                                @RequestParam(required = false) String environment,
                                                                @RequestParam(required = false) Instant since,
                                                                @RequestParam(defaultValue = "200") int limit) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> findings.query(blank(policyId), blank(teamId), blank(environment), since, Math.min(Math.max(limit, 1), 1000)));
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}

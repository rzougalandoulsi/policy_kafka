package com.itland.governance.api.administration;

import com.itland.governance.api.Callers;
import com.itland.governance.configuration.Blocking;
import com.itland.governance.infrastructure.persistence.AuditRepository;
import com.itland.governance.infrastructure.persistence.DeadLetterRepository;
import com.itland.governance.infrastructure.security.TeamAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration: audit and operations")
public class AuditController {

  private final AuditRepository audit;
  private final DeadLetterRepository deadLetters;
  private final TeamAuthorization authorization;
  private final Callers callers;
  private final Blocking blocking;
  private final Clock clock;

  public AuditController(AuditRepository audit, DeadLetterRepository deadLetters, TeamAuthorization authorization, Callers callers, Blocking blocking, Clock clock) {
    this.audit = audit;
    this.deadLetters = deadLetters;
    this.authorization = authorization;
    this.callers = callers;
    this.blocking = blocking;
    this.clock = clock;
  }

  @GetMapping("/audit")
  @Operation(summary = "Audit history", description = "Filter by targetType/targetId/requestId/actor/since.")
  public Mono<List<AuditRepository.AuditEntry>> audit(Authentication auth, @RequestParam(required = false) String targetType,
                                                      @RequestParam(required = false) String targetId, @RequestParam(required = false) String requestId,
                                                      @RequestParam(required = false) String actor, @RequestParam(required = false) Instant since,
                                                      @RequestParam(defaultValue = "200") int limit) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> audit.query(targetType, targetId, requestId, actor, since, Math.min(Math.max(limit, 1), 1000)));
  }

  @GetMapping("/dead-letters")
  @Operation(summary = "Open Kafka dead letters (poison or exhausted events)")
  public Mono<List<DeadLetterRepository.DeadLetter>> deadLetters(Authentication auth, @RequestParam(defaultValue = "100") int limit) {
    authorization.requireAuditor(callers.of(auth));
    return blocking.call(() -> deadLetters.listOpen(Math.min(Math.max(limit, 1), 1000)));
  }

  @PostMapping("/dead-letters/{id}/resolve")
  public Mono<Map<String, Object>> resolve(Authentication auth, @PathVariable long id) {
    var c = callers.of(auth);
    authorization.requireAdmin(c);
    return blocking.inTransaction(() -> {
      boolean done = deadLetters.resolve(id, clock.instant());
      audit.insert(clock.instant(), c.principalId(), "DEAD_LETTER_RESOLVED", "kafka_dead_letter", String.valueOf(id), null, Map.of());
      return Map.of("id", id, "resolved", done);
    });
  }
}

package com.itland.governance.application.exceptions;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.application.policy.NotFoundException;
import com.itland.governance.domain.exception.PolicyException;
import com.itland.governance.infrastructure.persistence.PolicyExceptionRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ExceptionAdminService {

  private static final Pattern POLICY_ID = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
  private static final Duration MAX_TTL = Duration.ofDays(365);

  private final PolicyExceptionRepository repository;
  private final AuditService audit;
  private final Clock clock;

  public ExceptionAdminService(PolicyExceptionRepository repository, AuditService audit, Clock clock) {
    this.repository = repository;
    this.audit = audit;
    this.clock = clock;
  }

  public record CreateCommand(String policyId, String principalId, String teamId, String environment, String resourcePattern,
                              List<String> actionTypes, Instant expiresAt, String reason) {}

  public PolicyException create(CreateCommand c, String actor) {
    Instant now = clock.instant();
    if (c.policyId() == null || !POLICY_ID.matcher(c.policyId()).matches()) throw new IllegalArgumentException("policyId must match " + POLICY_ID);
    if (c.teamId() == null || c.teamId().isBlank()) throw new IllegalArgumentException("teamId is required");
    if (c.reason() == null || c.reason().isBlank()) throw new IllegalArgumentException("reason is required");
    if (c.expiresAt() == null || !c.expiresAt().isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
    if (c.expiresAt().isAfter(now.plus(MAX_TTL))) throw new IllegalArgumentException("expiresAt may be at most " + MAX_TTL.toDays() + " days ahead");
    String env = c.environment() == null || c.environment().isBlank() ? "*" : c.environment();
    String pattern = c.resourcePattern() == null || c.resourcePattern().isBlank() ? "*" : c.resourcePattern();
    PolicyException e = new PolicyException("exc-" + UUID.randomUUID(), c.policyId(),
        c.principalId() == null || c.principalId().isBlank() ? null : c.principalId(), c.teamId(), env, pattern,
        c.actionTypes() == null ? List.of() : c.actionTypes(), c.reason(), c.expiresAt(), actor, now, null, null, null);
    repository.insert(e);
    audit.record(actor, "EXCEPTION_CREATED", "policy_exception", e.id(), Map.of(
        "policyId", e.policyId(), "teamId", e.teamId(), "environment", e.environment(), "principalId", String.valueOf(e.principalId()),
        "resourcePattern", e.resourcePattern(), "actionTypes", e.actionTypes(), "expiresAt", e.expiresAt().toString(), "reason", e.reason()));
    return e;
  }

  public PolicyException revoke(String id, String reason, String actor) {
    PolicyException existing = get(id);
    if (!repository.revoke(id, actor, reason, clock.instant())) {
      return existing; // already revoked: idempotent
    }
    audit.record(actor, "EXCEPTION_REVOKED", "policy_exception", id, Map.of("reason", reason == null ? "" : reason));
    return get(id);
  }

  public PolicyException get(String id) {
    return repository.findById(id).orElseThrow(() -> new NotFoundException("Exception " + id + " not found"));
  }

  public List<PolicyException> list(String teamId, boolean includeInactive) {
    return repository.list(teamId, includeInactive, clock.instant());
  }
}

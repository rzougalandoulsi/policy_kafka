package com.itland.governance.domain.exception;

import java.time.Instant;
import java.util.List;

/** A scoped, expiring exemption from one bypassable policy. Never a blanket whitelist. */
public record PolicyException(
    String id,
    String policyId,
    String principalId,      // null = any principal of the team
    String teamId,
    String environment,      // "*" = any
    String resourcePattern,  // glob
    List<String> actionTypes, // empty = any
    String reason,
    Instant expiresAt,
    String createdBy,
    Instant createdAt,
    String revokedBy,
    Instant revokedAt,
    String revokeReason) {

  public boolean activeAt(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  public boolean appliesTo(String principal, String team, String env) {
    return teamId.equals(team)
        && (principalId == null || principalId.equals(principal))
        && ("*".equals(environment) || environment.equals(env));
  }
}

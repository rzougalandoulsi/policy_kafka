package com.itland.governance.domain.usage;

import java.time.Instant;

/** Capacity an authorised plan holds until the Orchestrator reports a terminal event or the authorisation expires. */
public record QuotaReservation(String id, String teamId, String environment, String requestId, String planId, String decisionId,
                               UsageCounts deltas, Instant createdAt, Instant expiresAt, Instant releasedAt, String releaseReason) {

  public boolean open(Instant now) {
    return releasedAt == null && expiresAt.isAfter(now);
  }
}

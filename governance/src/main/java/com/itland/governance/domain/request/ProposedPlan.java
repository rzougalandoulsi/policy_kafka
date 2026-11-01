package com.itland.governance.domain.request;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The immutable plan proposed by the Orchestrator for one request (stored verbatim). */
public record ProposedPlan(
    String planId,
    String requestId,
    String planDigest,
    String manifestDigest,
    long stateVersion,
    List<PlannedActionView> actions,
    String eventId,
    Instant receivedAt) {

  public record PlannedActionView(String actionId, String resourceType, String resourceName, String actionType, Map<String, String> payload) {}
}

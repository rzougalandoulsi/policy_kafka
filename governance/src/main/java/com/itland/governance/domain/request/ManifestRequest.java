package com.itland.governance.domain.request;

import com.itland.governance.domain.manifest.Manifest;

import java.time.Instant;

public record ManifestRequest(
    String id,
    String principalId,
    String idempotencyKey,
    String teamId,
    String repositoryId,
    String environment,
    Manifest manifest,
    String manifestDigest,
    RequestStatus status,
    String planId,
    String planDigest,
    Long stateVersion,
    String failureReason,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {}

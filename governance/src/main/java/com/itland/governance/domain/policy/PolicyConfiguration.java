package com.itland.governance.domain.policy;

import java.time.Instant;
import java.util.Map;

public record PolicyConfiguration(
    String id, long version, Map<String, Object> content, VersionStatus status, String comment,
    String createdBy, Instant createdAt, Instant activatedAt, Instant retiredAt) {}

package com.itland.governance.domain.policy;

import java.time.Instant;
import java.util.Map;

/** Metadata of a Rego bundle version; the tarball bytes are loaded separately. */
public record PolicyBundleVersion(
    String id, String bundleName, String revision, String sha256, Map<String, Object> manifest, Map<String, Object> testReport,
    VersionStatus status, String comment, String uploadedBy, Instant uploadedAt, Instant activatedAt,
    Instant retiredAt, String failureReason) {}

package com.itland.governance.domain.policy;

import java.util.Map;

/** The consistent policy/configuration pair a single decision is evaluated against. */
public record PolicySnapshot(long configurationVersion, Map<String, Object> configuration, String bundleRevision) {}

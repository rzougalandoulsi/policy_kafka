package com.itland.governance.domain.policy;

import java.util.Map;

/** The consistent (configuration version, policy release) pair one decision is evaluated against. Release is null when no bundle is active. */
public record PolicySnapshot(long configurationVersion, Map<String, Object> configuration, PolicyRelease release) {}

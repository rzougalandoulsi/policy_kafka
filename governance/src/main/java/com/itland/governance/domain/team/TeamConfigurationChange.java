package com.itland.governance.domain.team;

import java.time.Instant;
import java.util.Map;

/** One entry of the team configuration history (append-only). */
public record TeamConfigurationChange(long id, String teamId, long version, Map<String, Object> content, String action,
                                      String changedBy, Instant changedAt, String comment) {}

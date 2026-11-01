package com.itland.governance.domain.team;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-team governance parameters supplied to OPA as {@code input.team}: authorised topic prefixes, quotas per resource
 * type, allowed-role override and free-form environment parameters. Versioned and audited; Governance is the system of
 * record ({@code source = governance}) unless an enterprise registry owns the data ({@code source = external}, read-only
 * for admins unless they explicitly take ownership back). A team without a stored row evaluates against
 * {@link #defaults(String, String)} ({@code source = default}, version 0).
 */
public record TeamConfiguration(
    String teamId,
    long version,
    List<String> authorizedPrefixes,
    Map<String, Long> quotas,
    List<String> allowedRoles,
    Map<String, Object> environments,
    String source,
    String sourceVersion,
    Instant syncedAt,
    String comment,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt) {

  public static final String SOURCE_GOVERNANCE = "governance";
  public static final String SOURCE_EXTERNAL = "external";
  public static final String SOURCE_DEFAULT = "default";

  /** Quota keys of input contract 2.0 (usage counters carry the same keys). */
  public static final List<String> QUOTA_KEYS = List.of(
      "topics", "topicPartitions", "schemas", "identityPools", "serviceAccounts", "connectors", "rbacBindings", "flinkStatements");

  public static final Set<String> SOURCES = Set.of(SOURCE_GOVERNANCE, SOURCE_EXTERNAL);

  public TeamConfiguration {
    authorizedPrefixes = authorizedPrefixes == null ? List.of() : List.copyOf(authorizedPrefixes);
    quotas = quotas == null ? Map.of() : Map.copyOf(new TreeMap<>(quotas));
    allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
    environments = environments == null ? Map.of() : Map.copyOf(environments);
    source = source == null ? SOURCE_GOVERNANCE : source;
  }

  /** What a team without stored configuration evaluates against: one prefix {@code <teamId><separator>}, no quotas. */
  public static TeamConfiguration defaults(String teamId, String prefixSeparator) {
    return new TeamConfiguration(teamId, 0, List.of(teamId.toLowerCase(java.util.Locale.ROOT) + prefixSeparator), Map.of(), List.of(), Map.of(),
        SOURCE_DEFAULT, null, null, null, "system", null, null);
  }

  public boolean stored() {
    return !SOURCE_DEFAULT.equals(source);
  }

  public boolean external() {
    return SOURCE_EXTERNAL.equals(source);
  }

  /** The editable part, as stored in the history table. */
  public Map<String, Object> content() {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("authorizedPrefixes", authorizedPrefixes);
    out.put("quotas", quotas);
    out.put("allowedRoles", allowedRoles);
    out.put("environments", environments);
    out.put("source", source);
    out.put("sourceVersion", sourceVersion);
    return out;
  }
}

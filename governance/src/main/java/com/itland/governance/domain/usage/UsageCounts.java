package com.itland.governance.domain.usage;

import com.itland.governance.domain.team.TeamConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable counters per quota key (input contract 2.0 {@code usage.current/reserved/delta}). Unknown keys count 0. */
public record UsageCounts(Map<String, Long> values) {

  public static final UsageCounts ZERO = new UsageCounts(Map.of());

  public UsageCounts {
    Map<String, Long> full = new LinkedHashMap<>();
    for (String k : TeamConfiguration.QUOTA_KEYS) full.put(k, 0L);
    if (values != null) values.forEach((k, v) -> full.put(k, v == null ? 0L : v));
    values = Map.copyOf(full);
  }

  public static UsageCounts of(long topics, long topicPartitions, long identityPools, long rbacBindings) {
    return new UsageCounts(Map.of("topics", topics, "topicPartitions", topicPartitions, "identityPools", identityPools, "rbacBindings", rbacBindings));
  }

  public long get(String key) {
    return values.getOrDefault(key, 0L);
  }

  public UsageCounts plus(UsageCounts other) {
    Map<String, Long> out = new LinkedHashMap<>(values);
    other.values.forEach((k, v) -> out.merge(k, v, Long::sum));
    return new UsageCounts(out);
  }

  public UsageCounts minus(UsageCounts other) {
    Map<String, Long> out = new LinkedHashMap<>(values);
    other.values.forEach((k, v) -> out.merge(k, -v, Long::sum));
    return new UsageCounts(out);
  }

  public boolean isZero() {
    return values.values().stream().allMatch(v -> v == 0L);
  }

  /** Keys sorted, as serialised into OPA input and jsonb columns. */
  public Map<String, Long> sorted() {
    return new java.util.TreeMap<>(values);
  }
}

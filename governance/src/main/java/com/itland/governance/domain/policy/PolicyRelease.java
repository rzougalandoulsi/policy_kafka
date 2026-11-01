package com.itland.governance.domain.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The tested set of bundle revisions Governance expects OPA to run: one revision per bundle name. The id is derived
 * from the sorted (name=revision) pairs, so the same set always gets the same id wherever it is computed
 * (policies/build.sh writes the same value into dist/release.json).
 */
public record PolicyRelease(String id, Map<String, String> revisions) {

  public PolicyRelease {
    revisions = Collections.unmodifiableMap(new TreeMap<>(revisions));
  }

  public static PolicyRelease of(Map<String, String> revisions) {
    return new PolicyRelease(idOf(revisions), revisions);
  }

  public static String idOf(Map<String, String> revisions) {
    String joined = new TreeMap<>(revisions).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
      return "rel-" + HexFormat.of().formatHex(digest).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Bundle names of {@code required} that have no revision in this release. */
  public Set<String> missing(Collection<String> required) {
    Set<String> missing = new TreeSet<>(required);
    missing.removeAll(revisions.keySet());
    return missing;
  }

  public boolean complete(Collection<String> required) {
    return missing(required).isEmpty();
  }
}

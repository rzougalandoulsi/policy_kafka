package com.itland.platform.contracts;

import com.itland.orchestrator.avro.PoolSpec;
import com.itland.orchestrator.avro.RbacSpec;
import com.itland.orchestrator.avro.RepoDesired;
import com.itland.orchestrator.avro.TopicSpec;
import com.itland.platform.avro.PlannedAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Deterministic digests shared by Governance and the Orchestrator so both sides compute
 * byte-identical values for the same manifest or plan. The canonical form is a sorted,
 * line-oriented text rendering (no JSON library involved, so no dependency on field order
 * or serializer settings).
 */
public final class Digests {

  private Digests() {}

  public static String manifestDigest(RepoDesired m) {
    StringBuilder sb = new StringBuilder();
    sb.append("team=").append(m.getTeamId()).append('\n');
    sb.append("repo=").append(m.getRepoId()).append('\n');
    sb.append("env=").append(m.getEnvironment()).append('\n');
    m.getTopics().stream().sorted(Comparator.comparing(TopicSpec::getName)).forEach(t ->
        sb.append("topic=").append(t.getName())
          .append("|partitions=").append(t.getPartitions() == null ? "" : t.getPartitions())
          .append("|config=").append(sortedMap(t.getConfig())).append('\n'));
    m.getIdentityPools().stream().sorted(Comparator.comparing(PoolSpec::getName)).forEach(p -> {
      sb.append("pool=").append(p.getName())
        .append("|principals=").append(p.getPrincipals().stream().sorted().collect(Collectors.joining(",")))
        .append('\n');
      p.getRbacs().stream().map(Digests::rbacKey).sorted().forEach(k -> sb.append("poolrbac=").append(p.getName()).append('|').append(k).append('\n'));
    });
    m.getRbacs().stream().map(Digests::rbacKey).sorted().forEach(k -> sb.append("rbac=").append(k).append('\n'));
    return sha256(sb.toString());
  }

  /** Plan digest binds the exact action list to the manifest and the confirmed-state version it was computed against. */
  public static String planDigest(List<PlannedAction> actions, String manifestDigest, long stateVersion) {
    StringBuilder sb = new StringBuilder();
    sb.append("manifest=").append(manifestDigest).append('\n');
    sb.append("stateVersion=").append(stateVersion).append('\n');
    actions.stream().map(Digests::canonicalAction).sorted().forEach(a -> sb.append(a).append('\n'));
    return sha256(sb.toString());
  }

  /** Stable, content-derived action identifier (same action in two plans gets the same id). */
  public static String actionId(String resourceType, String resourceName, String actionType) {
    return sha256(resourceType + "|" + resourceName + "|" + actionType).substring(0, 24);
  }

  public static String rbacKey(RbacSpec r) {
    return r.getPrincipal() + "#" + r.getResource() + "#" + r.getRole();
  }

  public static String sha256(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String canonicalAction(PlannedAction a) {
    return "action=" + a.getResourceType() + "|" + a.getResourceName() + "|" + a.getActionType()
        + "|" + sortedMap(a.getPayload());
  }

  private static String sortedMap(Map<String, String> map) {
    return new TreeMap<>(map).entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(",", "{", "}"));
  }
}

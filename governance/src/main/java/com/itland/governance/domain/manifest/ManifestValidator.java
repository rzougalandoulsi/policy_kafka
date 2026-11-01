package com.itland.governance.domain.manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Structural validation (shape, duplicates, ranges). Policy rules live in OPA, not here. */
public final class ManifestValidator {

  private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  private static final Pattern ENVIRONMENT = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
  private static final Pattern TOPIC_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,249}$");

  private ManifestValidator() {}

  public static List<String> validate(Manifest m) {
    List<String> errors = new ArrayList<>();
    if (m == null) {
      return List.of("manifest is required");
    }
    if (m.teamId() == null || !IDENTIFIER.matcher(m.teamId()).matches()) errors.add("teamId is required and must match " + IDENTIFIER);
    if (m.repositoryId() == null || !IDENTIFIER.matcher(m.repositoryId()).matches()) errors.add("repositoryId is required and must match " + IDENTIFIER);
    if (m.environment() == null || !ENVIRONMENT.matcher(m.environment()).matches()) errors.add("environment is required and must match " + ENVIRONMENT);
    if (m.resources() == null) {
      errors.add("resources is required");
      return errors;
    }
    Set<String> topicNames = new HashSet<>();
    for (Manifest.TopicResource t : m.resources().topics()) {
      if (t.name() == null || !TOPIC_NAME.matcher(t.name()).matches()) {
        errors.add("topic name is required and must match " + TOPIC_NAME);
        continue;
      }
      if (!topicNames.add(t.name())) errors.add("duplicate topic " + t.name());
      if (t.partitions() != null && (t.partitions() < 1 || t.partitions() > 10_000)) errors.add("topic " + t.name() + ": partitions must be between 1 and 10000");
      if (t.retentionMs() != null && t.retentionMs() < -1) errors.add("topic " + t.name() + ": retentionMs must be >= -1");
      t.config().forEach((k, v) -> {
        if (k == null || k.isBlank() || v == null) errors.add("topic " + t.name() + ": config entries need a key and a value");
      });
    }
    Set<String> rbacKeys = new HashSet<>();
    for (Manifest.RbacResource r : m.resources().rbac()) {
      validateRbac(r, "rbac", rbacKeys, errors);
    }
    Set<String> poolNames = new HashSet<>();
    for (Manifest.IdentityPoolResource p : m.resources().identityPools()) {
      if (p.name() == null || !IDENTIFIER.matcher(p.name()).matches()) {
        errors.add("identity pool name is required and must match " + IDENTIFIER);
        continue;
      }
      if (!poolNames.add(p.name())) errors.add("duplicate identity pool " + p.name());
      p.principals().forEach(pr -> { if (pr == null || pr.isBlank()) errors.add("identity pool " + p.name() + ": blank principal"); });
      p.rbac().forEach(r -> validateRbac(r, "identityPools[" + p.name() + "].rbac", rbacKeys, errors));
    }
    return errors;
  }

  private static void validateRbac(Manifest.RbacResource r, String where, Set<String> keys, List<String> errors) {
    if (r.principal() == null || r.principal().isBlank()) errors.add(where + ": principal is required");
    if (r.role() == null || r.role().isBlank()) errors.add(where + ": role is required");
    if (r.principal() != null && r.role() != null && !keys.add(r.principal() + "#" + r.resource() + "#" + r.role())) {
      errors.add(where + ": duplicate binding " + r.principal() + "/" + r.role() + "/" + r.resource());
    }
  }
}

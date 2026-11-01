package com.itland.governance.domain.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestValidatorTest {

  private static Manifest manifest(List<Manifest.TopicResource> topics, List<Manifest.RbacResource> rbac) {
    return new Manifest("team-a", "repo-1", "development", new Manifest.Resources(topics, rbac, List.of()));
  }

  @Test
  void validManifestHasNoErrors() {
    assertThat(ManifestValidator.validate(manifest(List.of(new Manifest.TopicResource("team-a.orders", 6, 86400000L, Map.of())),
        List.of(new Manifest.RbacResource("sa-1", "DeveloperRead", "topic:team-a.orders"))))).isEmpty();
  }

  @Test
  void duplicatesAndRangesAreReported() {
    List<String> errors = ManifestValidator.validate(manifest(List.of(
        new Manifest.TopicResource("team-a.orders", 0, null, Map.of()),
        new Manifest.TopicResource("team-a.orders", 6, -5L, Map.of())), List.of(
        new Manifest.RbacResource("sa", "Role", "r"), new Manifest.RbacResource("sa", "Role", "r"))));
    assertThat(errors).anyMatch(e -> e.contains("duplicate topic"))
        .anyMatch(e -> e.contains("partitions must be between"))
        .anyMatch(e -> e.contains("retentionMs"))
        .anyMatch(e -> e.contains("duplicate binding"));
  }

  @Test
  void identifiersAreChecked() {
    Manifest bad = new Manifest("team a", "", "Prod!", new Manifest.Resources(List.of(), List.of(), List.of()));
    assertThat(ManifestValidator.validate(bad)).hasSize(3);
    assertThat(ManifestValidator.validate(null)).containsExactly("manifest is required");
  }
}

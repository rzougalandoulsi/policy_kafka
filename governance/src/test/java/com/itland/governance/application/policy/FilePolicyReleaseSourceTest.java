package com.itland.governance.application.policy;

import com.itland.governance.configuration.Json;
import com.itland.governance.domain.policy.PolicyRelease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilePolicyReleaseSourceTest {

  static final Json JSON = new Json(JsonMapper.builder().build());
  static final Map<String, String> REVISIONS = Map.of("core", "core-1", "manifests", "manifests-1", "naming", "naming-1",
      "quotas", "quotas-1", "resources", "resources-1", "changes", "changes-1");

  static String manifest(Map<String, String> revisions, String declaredId) {
    StringBuilder b = new StringBuilder("{");
    if (declaredId != null) b.append("\"releaseId\":\"").append(declaredId).append("\",");
    b.append("\"source\":\"candidate\",\"bundles\":{");
    boolean first = true;
    for (var e : new java.util.TreeMap<>(revisions).entrySet()) {
      if (!first) b.append(',');
      first = false;
      b.append('"').append(e.getKey()).append("\":{\"revision\":\"").append(e.getValue()).append("\",\"sha256\":\"ab\",\"url\":\"https://x/y\"}");
    }
    return b.append("}}").toString();
  }

  @Test
  void readsTheReleaseAndComputesTheSameIdAsPolicyRelease(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("release.json");
    PolicyRelease expected = PolicyRelease.of(REVISIONS);
    Files.writeString(file, manifest(REVISIONS, expected.id()));
    FilePolicyReleaseSource source = new FilePolicyReleaseSource(file, JSON);

    assertThat(source.mode()).isEqualTo("release");
    assertThat(source.local()).isFalse();
    assertThat(source.expected()).contains(expected);
    assertThat(source.description()).startsWith("file:");
  }

  @Test
  void aDeclaredIdThatDoesNotMatchTheRevisionsYieldsNoRelease(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("release.json");
    Files.writeString(file, manifest(REVISIONS, "rel-000000000000"));
    assertThat(new FilePolicyReleaseSource(file, JSON).expected()).isEmpty();
  }

  @Test
  void missingOrEmptyFileYieldsNoRelease(@TempDir Path dir) throws Exception {
    assertThat(new FilePolicyReleaseSource(dir.resolve("absent.json"), JSON).expected()).isEmpty();
    Path empty = dir.resolve("empty.json");
    Files.writeString(empty, "{\"releaseId\":\"rel-000000000000\",\"bundles\":{}}");
    assertThat(new FilePolicyReleaseSource(empty, JSON).expected()).isEmpty();
  }

  @Test
  void aChangedFileIsReReadWithoutRestart(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("release.json");
    Files.writeString(file, manifest(REVISIONS, null));
    FilePolicyReleaseSource source = new FilePolicyReleaseSource(file, JSON);
    PolicyRelease first = source.expected().orElseThrow();

    Map<String, String> changed = new java.util.TreeMap<>(REVISIONS);
    changed.put("naming", "naming-2");
    Files.writeString(file, manifest(changed, null));
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));

    PolicyRelease second = source.expected().orElseThrow();
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(second.revisions()).containsEntry("naming", "naming-2");
  }
}

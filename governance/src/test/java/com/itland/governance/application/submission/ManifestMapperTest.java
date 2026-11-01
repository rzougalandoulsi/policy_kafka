package com.itland.governance.application.submission;

import com.itland.governance.domain.manifest.Manifest;
import com.itland.orchestrator.avro.RepoDesired;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestMapperTest {

  @Test
  void mapsOntoRepoDesiredWithoutLoss() {
    Manifest m = new Manifest("team-a", "repo-1", "production", new Manifest.Resources(
        List.of(new Manifest.TopicResource("team-a.orders", 12, 604800000L, Map.of("cleanup.policy", "compact"))),
        List.of(new Manifest.RbacResource("sa-1", "DeveloperRead", null)),
        List.of(new Manifest.IdentityPoolResource("pool", List.of("p1"), List.of(new Manifest.RbacResource("p1", "DeveloperWrite", "r"))))));
    RepoDesired d = ManifestMapper.toAvro(m);
    assertThat(d.getTeamId()).isEqualTo("team-a");
    assertThat(d.getRepoId()).isEqualTo("repo-1");
    assertThat(d.getEnvironment()).isEqualTo("production");
    assertThat(d.getTopics().get(0).getPartitions()).isEqualTo(12);
    assertThat(d.getTopics().get(0).getConfig()).containsEntry("retention.ms", "604800000").containsEntry("cleanup.policy", "compact");
    assertThat(d.getRbacs().get(0).getResource()).isEmpty();
    assertThat(d.getIdentityPools().get(0).getRbacs().get(0).getRole()).isEqualTo("DeveloperWrite");
    assertThat(ManifestMapper.digest(m)).hasSize(64).isEqualTo(ManifestMapper.digest(m));
  }
}

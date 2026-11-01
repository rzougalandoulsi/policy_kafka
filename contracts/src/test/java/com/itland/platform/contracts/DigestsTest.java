package com.itland.platform.contracts;

import com.itland.orchestrator.avro.RbacSpec;
import com.itland.orchestrator.avro.RepoDesired;
import com.itland.orchestrator.avro.TopicSpec;
import com.itland.platform.avro.PlannedAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DigestsTest {

  @Test
  void manifestDigestIsOrderIndependent() {
    RepoDesired a = manifest(List.of(topic("b", 3), topic("a", 1)));
    RepoDesired b = manifest(List.of(topic("a", 1), topic("b", 3)));
    assertThat(Digests.manifestDigest(a)).isEqualTo(Digests.manifestDigest(b)).hasSize(64);
  }

  @Test
  void manifestDigestChangesWithContent() {
    assertThat(Digests.manifestDigest(manifest(List.of(topic("a", 1)))))
        .isNotEqualTo(Digests.manifestDigest(manifest(List.of(topic("a", 2)))));
  }

  @Test
  void planDigestBindsStateVersionAndIsOrderIndependent() {
    PlannedAction x = action("TOPIC", "a", "TOPIC_CREATE");
    PlannedAction y = action("TOPIC", "b", "TOPIC_DELETE");
    assertThat(Digests.planDigest(List.of(x, y), "m", 1)).isEqualTo(Digests.planDigest(List.of(y, x), "m", 1));
    assertThat(Digests.planDigest(List.of(x, y), "m", 1)).isNotEqualTo(Digests.planDigest(List.of(x, y), "m", 2));
    assertThat(Digests.planDigest(List.of(x, y), "m", 1)).isNotEqualTo(Digests.planDigest(List.of(x, y), "n", 1));
  }

  @Test
  void rbacKeyMatchesLegacyFormat() {
    assertThat(Digests.rbacKey(RbacSpec.newBuilder().setPrincipal("p").setResource("r").setRole("Role").build()))
        .isEqualTo("p#r#Role");
  }

  private static RepoDesired manifest(List<TopicSpec> topics) {
    return RepoDesired.newBuilder().setTeamId("team-a").setRepoId("repo-1").setEnvironment("development")
        .setTopics(topics).build();
  }

  private static TopicSpec topic(String name, int partitions) {
    return TopicSpec.newBuilder().setName(name).setPartitions(partitions).setConfig(Map.of("retention.ms", "1000")).build();
  }

  private static PlannedAction action(String type, String name, String actionType) {
    return PlannedAction.newBuilder().setActionId(Digests.actionId(type, name, actionType))
        .setResourceType(type).setResourceName(name).setActionType(actionType).setPayload(Map.of("name", name)).build();
  }
}

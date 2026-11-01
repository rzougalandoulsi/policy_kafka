package com.itland.governance.application.usage;

import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.usage.RepositoryUsage;
import com.itland.governance.domain.usage.UsageCounts;
import com.itland.governance.domain.usage.UsageSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class UsageMathTest {

  static RepositoryUsage confirmed(long stateVersion, List<String> topics, Map<String, Integer> partitions) {
    return new RepositoryUsage("team-a", "repo-1", "development", stateVersion, topics, List.of("pool-1"), List.of("u#r#DeveloperRead"), partitions, Instant.EPOCH, Instant.EPOCH);
  }

  static ProposedPlan.PlannedActionView action(String type, String name, Map<String, String> payload) {
    return new ProposedPlan.PlannedActionView("a", type.startsWith("TOPIC") ? "TOPIC" : type.startsWith("RBAC") ? "RBAC" : "IDENTITY_POOL", name, type, payload);
  }

  @Test
  void confirmedCountsUseDeclaredPartitionsAndDefaultToOne() {
    UsageCounts c = UsageMath.countsOf(confirmed(3, List.of("a", "b"), Map.of("a", 6)));
    assertThat(c.get("topics")).isEqualTo(2);
    assertThat(c.get("topicPartitions")).isEqualTo(7);
    assertThat(c.get("identityPools")).isEqualTo(1);
    assertThat(c.get("rbacBindings")).isEqualTo(1);
    assertThat(c.get("schemas")).isZero();
  }

  @Test
  void manifestDeltaIsDeclaredMinusThisRepositoryOnly() {
    Manifest m = new Manifest("team-a", "repo-1", "development", new Manifest.Resources(
        List.of(new Manifest.TopicResource("a", 6, null, Map.of()), new Manifest.TopicResource("c", null, null, Map.of())),
        List.of(), List.of(new Manifest.IdentityPoolResource("pool-1", List.of(), List.of(new Manifest.RbacResource("p", "DeveloperRead", "r"))))));
    UsageCounts delta = UsageMath.manifestDelta(m, confirmed(3, List.of("a", "b"), Map.of("a", 6)));
    assertThat(delta.get("topics")).isZero();              // 2 declared - 2 confirmed
    assertThat(delta.get("topicPartitions")).isZero();     // 6 + 1 declared - (6 + 1) confirmed
    assertThat(delta.get("identityPools")).isZero();
    assertThat(delta.get("rbacBindings")).isZero();
    assertThat(UsageMath.manifestDelta(m, null).get("topics")).isEqualTo(2);
  }

  @Test
  void actionsDeltaCountsCreatesDeletesAndPartitionIncreases() {
    RepositoryUsage current = confirmed(3, List.of("old", "grow"), Map.of("old", 4, "grow", 3));
    UsageCounts delta = UsageMath.actionsDelta(List.of(
        action("TOPIC_CREATE", "new", Map.of("partitions", "12")),
        action("TOPIC_CREATE", "default", Map.of()),
        action("TOPIC_DELETE", "old", Map.of()),
        action("TOPIC_UPDATE", "grow", Map.of("partitions", "9")),
        action("IDENTITY_POOL_CREATE", "p2", Map.of()),
        action("RBAC_REVOKE", "k", Map.of()),
        action("RBAC_ASSIGN", "k2", Map.of())), current);
    assertThat(delta.get("topics")).isEqualTo(1);           // +2 created -1 deleted
    assertThat(delta.get("topicPartitions")).isEqualTo(15); // 12 + 1 - 4 + (9 - 3)
    assertThat(delta.get("identityPools")).isEqualTo(1);
    assertThat(delta.get("rbacBindings")).isZero();
  }

  @Test
  void freshnessGuardOnlyBitesWhenThePlanIsNewerThanTheSnapshot() {
    UsageSnapshot none = UsageSnapshot.empty();
    assertThat(none.staleFor(0)).isFalse();
    assertThat(none.staleFor(1)).isTrue();
    UsageSnapshot at4 = new UsageSnapshot(UsageCounts.ZERO, UsageCounts.ZERO, UsageCounts.ZERO, Map.of(), Map.of(), null, Map.of(), true, OptionalLong.of(4));
    assertThat(at4.staleFor(4)).isFalse();
    assertThat(at4.staleFor(5)).isTrue();
    assertThat(at4.staleFor(2)).isFalse();
  }

  @Test
  void countsArithmeticKeepsAllQuotaKeys() {
    UsageCounts a = UsageCounts.of(1, 2, 3, 4);
    UsageCounts sum = a.plus(new UsageCounts(Map.of("schemas", 2L)));
    assertThat(sum.sorted()).containsEntry("schemas", 2L).containsEntry("topics", 1L).containsKeys("connectors", "flinkStatements", "serviceAccounts");
    assertThat(a.minus(a).isZero()).isTrue();
  }
}

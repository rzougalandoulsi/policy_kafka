package com.itland.governance.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.exception.PolicyException;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.policy.PolicyRelease;
import com.itland.governance.domain.policy.PolicySnapshot;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.team.TeamConfiguration;
import com.itland.governance.domain.usage.UsageCounts;
import com.itland.governance.domain.usage.UsageSnapshot;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembled OPA input must validate against contracts/opa/input.schema.json (contract 2.0), the same document the
 * policy repository validates its fixtures against. A field renamed on one side without the other fails here.
 */
class OpaInputContractTest {

  private static final JsonMapper JACKSON3 = JsonMapper.builder().build();
  private static final ObjectMapper JACKSON2 = new ObjectMapper();

  static JsonSchema schema(String name) {
    try (InputStream in = OpaInputContractTest.class.getResourceAsStream("/opa/" + name)) {
      assertThat(in).as("schema %s packaged in the contracts jar", name).isNotNull();
      SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(new String(in.readAllBytes()), InputFormat.JSON, config);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static Manifest manifest() {
    return new Manifest("team-a", "repo-1", "development", new Manifest.Resources(
        List.of(new Manifest.TopicResource("team-a.orders", 6, 86400000L, Map.of("cleanup.policy", "delete")),
            new Manifest.TopicResource("team-a.events", null, null, Map.of())),
        List.of(new Manifest.RbacResource("User:sa-1", "DeveloperRead", "Topic:team-a.orders")),
        List.of(new Manifest.IdentityPoolResource("pool-1", List.of("sub-1"), List.of(new Manifest.RbacResource("pool-1", "DeveloperWrite", "Topic:team-a.events"))))));
  }

  static PolicySnapshot snapshot() {
    Map<String, Object> configuration = Map.of(
        "naming", Map.of("topicPattern", "^[a-z]+$", "requireTeamPrefix", true, "teamPrefixSeparator", "."),
        "rbac", Map.of("allowedRoles", List.of("DeveloperRead")),
        "limits", Map.of("maxTopicsPerManifest", 50),
        "destructive", Map.of(),
        "approvals", Map.of(),
        "enforcement", Map.of("AUTHORIZED_PREFIX_REQUIRED", "shadow"));
    return new PolicySnapshot(7, configuration, PolicyRelease.of(Map.of("core", "core-1")));
  }

  static UsageSnapshot usage() {
    return new UsageSnapshot(UsageCounts.of(4, 24, 1, 3), UsageCounts.of(1, 6, 0, 0), UsageCounts.of(2, 7, 1, 2),
        Map.of("repo-1", UsageCounts.of(1, 6, 0, 1), "repo-2", UsageCounts.of(3, 18, 1, 2)), Map.of("repo-1", 4L, "repo-2", 9L),
        Duration.ofSeconds(3), Map.of("TOPIC:team-a.orders", "repo-2"), true, OptionalLong.of(4));
  }

  static TeamConfiguration team() {
    return new TeamConfiguration("team-a", 3, List.of("team-a.", "orders."), Map.of("topics", 25L, "topicPartitions", 100L), List.of("DeveloperRead"),
        Map.of("production", Map.of("changeWindow", "weekdays")), TeamConfiguration.SOURCE_GOVERNANCE, null, null, "c", "admin-1", Instant.EPOCH, Instant.EPOCH);
  }

  private static Set<ValidationMessage> validate(JsonSchema schema, Object document) {
    try {
      JsonNode node = JACKSON2.readTree(JACKSON3.writeValueAsString(document));
      return schema.validate(node);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void stageOneInputMatchesTheContract() {
    PolicyException exception = new PolicyException("exc-1", "AUTHORIZED_PREFIX_REQUIRED", null, "team-a", "*", "legacy.*", List.of(), "r",
        Instant.parse("2027-01-01T00:00:00Z"), "admin-1", Instant.EPOCH, null, null, null);
    Map<String, Object> input = OpaInputAssembler.build(GovernanceStage.REQUEST, "dev-1", Set.of("dev"), manifest(), "req-1", "digest", null,
        snapshot(), List.of(exception), team(), usage(), Instant.parse("2026-11-01T10:00:00Z"), "2.0");
    assertThat(validate(schema("input.schema.json"), input)).isEmpty();
    assertThat(input).containsEntry("contractVersion", "2.0").containsEntry("now", "2026-11-01T10:00:00Z");
    @SuppressWarnings("unchecked") Map<String, Object> team = (Map<String, Object>) input.get("team");
    assertThat(team).containsEntry("authorizedPrefixes", List.of("team-a.", "orders.")).containsEntry("source", "governance").containsEntry("configurationVersion", 3L);
    @SuppressWarnings("unchecked") Map<String, Object> usage = (Map<String, Object>) input.get("usage");
    assertThat(usage).containsKeys("current", "reserved", "delta", "byRepository", "asOfStateVersions", "snapshotAge");
  }

  @Test
  void stageTwoInputMatchesTheContract() {
    ProposedPlan plan = new ProposedPlan("plan-1", "req-1", "pd", "md", 4,
        List.of(new ProposedPlan.PlannedActionView("a1", "TOPIC", "team-a.orders", "TOPIC_CREATE", Map.of("partitions", "6"))), "evt-1", Instant.EPOCH);
    TeamConfiguration defaults = TeamConfiguration.defaults("team-a", ".");
    Map<String, Object> input = OpaInputAssembler.build(GovernanceStage.ACTION_PLAN, "dev-1", Set.of(), manifest(), "req-1", "digest", plan,
        snapshot(), List.of(), defaults, UsageSnapshot.empty(), Instant.parse("2026-11-01T10:00:00Z"), "2.0");
    assertThat(validate(schema("input.schema.json"), input)).isEmpty();
    @SuppressWarnings("unchecked") Map<String, Object> team = (Map<String, Object>) input.get("team");
    assertThat(team).containsEntry("source", "default").containsEntry("configurationVersion", 0L).containsEntry("authorizedPrefixes", List.of("team-a."));
  }

  @Test
  void schemaRejectsAnInputWithoutTeamPrefixes() {
    Map<String, Object> input = new java.util.LinkedHashMap<>(OpaInputAssembler.build(GovernanceStage.REQUEST, "dev-1", Set.of(), manifest(), "req-1", "d", null,
        snapshot(), List.of(), team(), usage(), Instant.EPOCH, "2.0"));
    input.put("team", Map.of("id", "team-a"));
    assertThat(validate(schema("input.schema.json"), input)).isNotEmpty();
  }

  @Test
  void representativeDecisionMatchesTheOutputContract() throws Exception {
    String decision = """
        {"decision":"DENY","contractVersion":"2.0","ready":true,"bundles":{"naming":"2.0"},"revisions":{},
         "violations":[{"policyId":"TEAM_QUOTA_EXCEEDED","severity":"DENY","message":"m","resourceType":"QUOTA","resourceName":"topics",
                        "bypassable":true,"exempted":false,"exceptionId":null,"enforcement":"enforce"}],
         "approvals":[{"policyId":"PROD_TOPIC_DELETE_REQUIRES_APPROVAL","message":"m","resourceType":"TOPIC","resourceName":"t","enforcement":"shadow"}],
         "matchedPolicies":["TEAM_QUOTA_EXCEEDED"],"appliedExceptions":[]}
        """;
    assertThat(schema("output.schema.json").validate(JACKSON2.readTree(decision))).isEmpty();
  }
}

package com.itland.governance.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "governance")
public record GovernanceProperties(
    Kafka kafka,
    Opa opa,
    Security security,
    @DefaultValue Workflow workflow,
    @DefaultValue Outbox outbox,
    @DefaultValue Inbox inbox,
    @DefaultValue Usage usage) {

  public record Kafka(
      String bootstrapServers,
      String schemaRegistryUrl,
      @DefaultValue("requests.manifest") String requestsTopic,
      @DefaultValue("response.manifest") String responseTopic,
      @DefaultValue("governance") String consumerGroup,
      @DefaultValue("governance-producer") String producerClientId,
      @DefaultValue("true") boolean consumerEnabled,
      @DefaultValue("") String securityProtocol,
      @DefaultValue("") String saslMechanism,
      @DefaultValue("") String saslJaasConfig,
      @DefaultValue("") String schemaRegistryAuth,
      /** Compacted confirmed state (RepoConfirmed) the Orchestrator publishes; Governance only reads it (usage snapshot). */
      @DefaultValue("teams.repo.manifest") String repositoryStateTopic,
      @DefaultValue("governance-usage") String usageConsumerGroup,
      @DefaultValue("true") boolean usageConsumerEnabled) {}

  public record Opa(
      String url,
      @DefaultValue("/v1/data/governance/core/decision") String decisionPath,
      @DefaultValue("/v1/data/governance/core/policy_catalog") String catalogPath,
      @DefaultValue("/v1/data/governance/core/ready") String readyPath,
      /** Bundle names of the policy release OPA must run; every decision verifies all of their revisions. */
      @DefaultValue({"core", "manifests", "naming", "quotas", "resources", "changes"}) List<String> bundles,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("5s") Duration responseTimeout,
      @DefaultValue("2") int maxRetries,
      @DefaultValue("200ms") Duration retryBackoff,
      @DefaultValue("true") boolean verifyRevision,
      /** Shared secret OPA presents when pulling bundles from /internal/opa/bundles. */
      String bundleToken,
      /** Input contract version OpaInputAssembler produces (contracts/opa/input.schema.json). */
      @DefaultValue("2.0") String contractVersion,
      /** Core contract majors this build accepts in decisions; a major bump ships Governance first (both), then policies. */
      @DefaultValue({"1", "2"}) List<String> supportedContractMajors) {}

  public record Security(
      String issuerUri,
      @DefaultValue("teams") String teamsClaim,
      @DefaultValue("environments") String environmentsClaim,
      @DefaultValue("roles") String rolesClaim,
      @DefaultValue("governance-admin") String adminRole,
      @DefaultValue("governance-approver") String approverRole,
      @DefaultValue("governance-auditor") String auditorRole,
      @DefaultValue List<String> extraPermitPaths) {}

  public record Workflow(
      /** How long an AUTHORIZE_PLAN stays valid for the Orchestrator to release it (also the quota reservation TTL). */
      @DefaultValue("PT15M") Duration authorizationValidity,
      @DefaultValue("PT72H") Duration defaultApprovalTtl,
      @DefaultValue("3") int maxEvaluationAttempts,
      @DefaultValue("30s") Duration evaluationRetryBackoff) {}

  public record Outbox(
      @DefaultValue("500ms") Duration pollInterval,
      @DefaultValue("100") int batchSize,
      @DefaultValue("10") int maxAttempts,
      @DefaultValue("2s") Duration retryBackoff) {}

  public record Inbox(
      @DefaultValue("200") int pollRecords,
      @DefaultValue("5s") Duration retryPollInterval) {}

  public record Usage(
      /**
       * Stage 2 freshness guard: when the usage snapshot of the request's repository is older than the plan's
       * stateVersion the decision is ERROR and the inbox retries, so quotas are never computed on stale usage.
       */
      @DefaultValue("true") boolean requireFreshSnapshot) {}
}

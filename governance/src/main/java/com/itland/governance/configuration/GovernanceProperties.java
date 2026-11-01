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
    @DefaultValue Inbox inbox) {

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
      @DefaultValue("") String schemaRegistryAuth) {}

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
      String bundleToken) {}

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
      /** How long an AUTHORIZE_PLAN stays valid for the Orchestrator to release it. */
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
}

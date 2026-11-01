package com.itland.governance.infrastructure.kafka;

import com.itland.governance.application.submission.ManifestMapper;
import com.itland.governance.configuration.GovernanceProperties;
import com.itland.governance.domain.decision.PolicyViolation;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.infrastructure.persistence.OutboxRepository;
import com.itland.orchestrator.avro.RepoDesired;
import com.itland.platform.avro.AuthorizePlanPayload;
import com.itland.platform.avro.ManifestCommand;
import com.itland.platform.avro.ManifestCommandType;
import com.itland.platform.avro.RejectPlanPayload;
import com.itland.platform.avro.SubmitManifestPayload;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds Governance -> Orchestrator commands and stores them in the transactional outbox (same transaction as the
 * request state change). The {@link OutboxRelay} publishes them to requests.manifest keyed by teamId.
 */
@Component
public class ManifestCommandPublisher {

  private final OutboxRepository outbox;
  private final GovernanceProperties.Kafka kafka;
  private final GovernanceProperties.Workflow workflow;
  private final Clock clock;

  public ManifestCommandPublisher(OutboxRepository outbox, GovernanceProperties properties, Clock clock) {
    this.outbox = outbox;
    this.kafka = properties.kafka();
    this.workflow = properties.workflow();
    this.clock = clock;
  }

  public String enqueueSubmit(ManifestRequest request) {
    RepoDesired desired = ManifestMapper.toAvro(request.manifest());
    ManifestCommand cmd = base(request, ManifestCommandType.SUBMIT_MANIFEST, null, null, null)
        .setPayload(SubmitManifestPayload.newBuilder().setManifest(desired).setManifestDigest(request.manifestDigest()).build())
        .build();
    return enqueue(cmd, request.id());
  }

  public String enqueueAuthorize(ManifestRequest request, ProposedPlan plan, String decisionId) {
    Instant now = clock.instant();
    ManifestCommand cmd = base(request, ManifestCommandType.AUTHORIZE_PLAN, plan.planId(), plan.planDigest(), decisionId)
        .setPayload(AuthorizePlanPayload.newBuilder().setStateVersion(plan.stateVersion())
            .setAuthorizationExpiresAt(now.plus(workflow.authorizationValidity())).build())
        .build();
    return enqueue(cmd, request.id());
  }

  public String enqueueReject(ManifestRequest request, String planId, String planDigest, String decisionId, String reasonCode, List<PolicyViolation> violations) {
    ManifestCommand cmd = base(request, ManifestCommandType.REJECT_PLAN, planId, planDigest, decisionId)
        .setPayload(RejectPlanPayload.newBuilder().setReasonCode(reasonCode)
            .setViolations(violations.stream().map(v -> com.itland.platform.avro.PolicyViolation.newBuilder()
                .setPolicyId(v.policyId()).setSeverity(v.severity()).setMessage(v.message() == null ? "" : v.message())
                .setResourceType(v.resourceType()).setResourceName(v.resourceName()).build()).toList())
            .build())
        .build();
    return enqueue(cmd, request.id());
  }

  private ManifestCommand.Builder base(ManifestRequest r, ManifestCommandType type, String planId, String planDigest, String decisionId) {
    return ManifestCommand.newBuilder()
        .setCommandId(UUID.randomUUID().toString())
        .setCommandType(type)
        .setRequestId(r.id()).setTeamId(r.teamId()).setRepositoryId(r.repositoryId()).setEnvironment(r.environment())
        .setPlanId(planId).setPlanDigest(planDigest).setDecisionId(decisionId)
        .setOccurredAt(clock.instant());
  }

  private String enqueue(ManifestCommand cmd, String requestId) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("requestId", cmd.getRequestId());
    headers.put("commandType", cmd.getCommandType().name());
    if (cmd.getPlanId() != null) headers.put("planId", cmd.getPlanId());
    String id = "out-" + UUID.randomUUID();
    outbox.enqueue(id, kafka.requestsTopic(), cmd.getTeamId(), ManifestCommand.class.getName(), AvroJson.encode(cmd), headers, requestId, clock.instant());
    return id;
  }
}

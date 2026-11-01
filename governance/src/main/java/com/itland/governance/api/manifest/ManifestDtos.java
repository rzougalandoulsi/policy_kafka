package com.itland.governance.api.manifest;

import com.itland.governance.domain.decision.ApprovalRequirement;
import com.itland.governance.domain.decision.PolicyViolation;
import com.itland.governance.domain.manifest.Manifest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class ManifestDtos {
  private ManifestDtos() {}

  public record ManifestRequestBody(
      @NotBlank String teamId,
      @NotBlank String repositoryId,
      @NotBlank String environment,
      @NotNull @Valid ResourcesBody resources) {

    public Manifest toDomain() {
      return new Manifest(teamId, repositoryId, environment, new Manifest.Resources(
          resources.topics() == null ? List.of() : resources.topics().stream().map(t -> new Manifest.TopicResource(t.name(), t.partitions(), t.retentionMs(), t.config())).toList(),
          resources.rbac() == null ? List.of() : resources.rbac().stream().map(r -> new Manifest.RbacResource(r.principal(), r.role(), r.resource())).toList(),
          resources.identityPools() == null ? List.of() : resources.identityPools().stream().map(p -> new Manifest.IdentityPoolResource(p.name(), p.principals(),
              p.rbac() == null ? List.of() : p.rbac().stream().map(r -> new Manifest.RbacResource(r.principal(), r.role(), r.resource())).toList())).toList()));
    }
  }

  public record ResourcesBody(List<@Valid TopicBody> topics, List<@Valid RbacBody> rbac, List<@Valid IdentityPoolBody> identityPools) {}

  public record TopicBody(@NotBlank String name, Integer partitions, Long retentionMs, Map<String, String> config) {}

  public record RbacBody(@NotBlank String principal, @NotBlank String role, String resource) {}

  public record IdentityPoolBody(@NotBlank String name, List<String> principals, List<@Valid RbacBody> rbac) {}

  public record AcceptedResponse(String requestId, String status, String statusUrl, String decisionId) {}

  public record RejectedResponse(String requestId, String status, String decisionId, String policyRevision,
                                 List<PolicyViolation> violations, List<ApprovalRequirement> approvalRequirements) {}
}

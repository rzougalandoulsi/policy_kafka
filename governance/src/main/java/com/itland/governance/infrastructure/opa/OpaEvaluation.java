package com.itland.governance.infrastructure.opa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Raw OPA Data API response for data.governance.decision (with ?provenance=true). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpaEvaluation(Result result, Provenance provenance) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Result(
      String decision,
      List<Violation> violations,
      List<Approval> approvals,
      List<String> matchedPolicies,
      List<String> appliedExceptions,
      String revision) {
    public Result {
      violations = violations == null ? List.of() : violations;
      approvals = approvals == null ? List.of() : approvals;
      matchedPolicies = matchedPolicies == null ? List.of() : matchedPolicies;
      appliedExceptions = appliedExceptions == null ? List.of() : appliedExceptions;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Violation(String policyId, String severity, String message, String resourceType, String resourceName,
                          Boolean bypassable, Boolean exempted, String exceptionId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Approval(String policyId, String message, String resourceType, String resourceName) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Provenance(String version, Map<String, BundleInfo> bundles) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BundleInfo(String revision) {}

  /** Bundle revision OPA reports for the named bundle, or null when OPA has none loaded. */
  public String bundleRevision(String bundleName) {
    if (provenance == null || provenance.bundles() == null) return null;
    BundleInfo info = provenance.bundles().get(bundleName);
    return info == null ? null : info.revision();
  }
}

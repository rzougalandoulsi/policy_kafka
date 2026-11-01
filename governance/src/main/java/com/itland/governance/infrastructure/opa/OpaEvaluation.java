package com.itland.governance.infrastructure.opa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Raw OPA Data API response for data.governance.core.decision (with ?provenance=true). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpaEvaluation(Result result, Provenance provenance) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Result(
      String decision,
      List<Violation> violations,
      List<Approval> approvals,
      List<String> matchedPolicies,
      List<String> appliedExceptions,
      /** false when a required bundle is missing or has an incompatible contract version (composition not ready). */
      Boolean ready,
      /** contract version per loaded domain bundle. */
      Map<String, String> bundles,
      /** revision per bundle as OPA sees it (data.system.bundles). */
      Map<String, String> revisions,
      /** core's input/output contract version (absent from contract 1.x cores). */
      String contractVersion) {
    public Result {
      violations = violations == null ? List.of() : violations;
      approvals = approvals == null ? List.of() : approvals;
      matchedPolicies = matchedPolicies == null ? List.of() : matchedPolicies;
      appliedExceptions = appliedExceptions == null ? List.of() : appliedExceptions;
      bundles = bundles == null ? Map.of() : bundles;
      revisions = revisions == null ? Map.of() : revisions;
    }

    /** Major of the core contract; "1" when the core predates the field. */
    public String contractMajor() {
      String v = contractVersion == null || contractVersion.isBlank() ? "1.0" : contractVersion;
      int dot = v.indexOf('.');
      return dot < 0 ? v : v.substring(0, dot);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Violation(String policyId, String severity, String message, String resourceType, String resourceName,
                          Boolean bypassable, Boolean exempted, String exceptionId, String enforcement) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Approval(String policyId, String message, String resourceType, String resourceName, String enforcement) {}

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

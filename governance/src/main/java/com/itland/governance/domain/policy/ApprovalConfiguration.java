package com.itland.governance.domain.policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApprovalConfiguration(
    String id, long version, List<String> approverRoles, int requiredApprovals, boolean separationOfDuties,
    int ttlHours, VersionStatus status, String createdBy, Instant createdAt, Instant activatedAt) {

  @SuppressWarnings("unchecked")
  public static ApprovalConfiguration fromContent(String id, long version, Map<String, Object> content, VersionStatus status,
                                                  String createdBy, Instant createdAt, Instant activatedAt) {
    List<String> roles = content.get("approverRoles") instanceof List<?> l ? (List<String>) l : List.of("governance-approver");
    int required = content.get("requiredApprovals") instanceof Number n ? n.intValue() : 1;
    boolean sod = !(content.get("separationOfDuties") instanceof Boolean b) || b;
    int ttl = content.get("ttlHours") instanceof Number n ? n.intValue() : 72;
    return new ApprovalConfiguration(id, version, roles, required, sod, ttl, status, createdBy, createdAt, activatedAt);
  }

  public Map<String, Object> content() {
    return Map.of("approverRoles", approverRoles, "requiredApprovals", requiredApprovals,
        "separationOfDuties", separationOfDuties, "ttlHours", ttlHours);
  }
}

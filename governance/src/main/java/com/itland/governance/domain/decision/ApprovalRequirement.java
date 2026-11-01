package com.itland.governance.domain.decision;

public record ApprovalRequirement(String policyId, String message, String resourceType, String resourceName) {}

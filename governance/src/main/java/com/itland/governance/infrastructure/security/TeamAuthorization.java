package com.itland.governance.infrastructure.security;

import com.itland.governance.configuration.GovernanceProperties;
import org.springframework.stereotype.Component;

/** Resource-level authorization rules. Admin/approver/auditor roles come from configuration. */
@Component
public class TeamAuthorization {

  private final GovernanceProperties.Security security;

  public TeamAuthorization(GovernanceProperties properties) {
    this.security = properties.security();
  }

  public boolean isAdmin(CallerContext c) {
    return c.hasRole(security.adminRole());
  }

  public boolean isApprover(CallerContext c) {
    return c.hasRole(security.approverRole());
  }

  public boolean isAuditor(CallerContext c) {
    return c.hasRole(security.auditorRole()) || isAdmin(c);
  }

  public void requireSubmit(CallerContext c, String teamId, String environment) {
    if (!c.memberOf(teamId)) {
      throw new AccessDeniedException("Principal is not a member of team " + teamId);
    }
    if (!c.canAccessEnvironment(environment)) {
      throw new AccessDeniedException("Principal has no access to environment " + environment);
    }
  }

  /** Requesters, team members, approvers, auditors and admins may read a request. */
  public void requireView(CallerContext c, String ownerPrincipal, String teamId) {
    if (c.principalId().equals(ownerPrincipal) || c.memberOf(teamId) || isApprover(c) || isAuditor(c)) {
      return;
    }
    throw new AccessDeniedException("Principal may not view requests of team " + teamId);
  }

  public void requireAdmin(CallerContext c) {
    if (!isAdmin(c)) throw new AccessDeniedException("Administrator role required");
  }

  public void requireAuditor(CallerContext c) {
    if (!isAuditor(c)) throw new AccessDeniedException("Auditor role required");
  }

  public void requireApprover(CallerContext c) {
    if (!isApprover(c)) throw new AccessDeniedException("Approver role required");
  }
}

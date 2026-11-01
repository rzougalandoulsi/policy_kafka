package com.itland.governance.infrastructure.security;

import java.util.Set;

/** Identity and entitlements derived exclusively from the validated JWT. Never built from request bodies. */
public record CallerContext(String principalId, Set<String> teams, Set<String> environments, Set<String> roles) {

  public boolean memberOf(String teamId) {
    return teams.contains(teamId);
  }

  public boolean canAccessEnvironment(String environment) {
    return environments.contains(environment) || environments.contains("*");
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public static CallerContext system(String id) {
    return new CallerContext(id, Set.of(), Set.of("*"), Set.of());
  }
}

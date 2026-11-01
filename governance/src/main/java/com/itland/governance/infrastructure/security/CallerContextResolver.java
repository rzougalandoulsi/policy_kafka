package com.itland.governance.infrastructure.security;

import com.itland.governance.configuration.GovernanceProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class CallerContextResolver {

  private final GovernanceProperties.Security security;

  public CallerContextResolver(GovernanceProperties properties) {
    this.security = properties.security();
  }

  public CallerContext resolve(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      throw new AccessDeniedException("Unsupported authentication");
    }
    Jwt jwt = token.getToken();
    String subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new AccessDeniedException("Token has no subject");
    }
    return new CallerContext(subject,
        stringSet(jwt.getClaim(security.teamsClaim())),
        stringSet(jwt.getClaim(security.environmentsClaim())),
        stringSet(jwt.getClaim(security.rolesClaim())));
  }

  static Set<String> stringSet(Object claim) {
    if (claim == null) return Set.of();
    if (claim instanceof Collection<?> c) {
      Set<String> out = new LinkedHashSet<>();
      c.forEach(v -> out.add(String.valueOf(v)));
      return Set.copyOf(out);
    }
    if (claim instanceof String s) {
      return Set.copyOf(List.of(s.trim().split("[\\s,]+")));
    }
    return Set.of(String.valueOf(claim));
  }
}

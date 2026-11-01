package com.itland.governance.api;

import com.itland.governance.infrastructure.security.CallerContext;
import com.itland.governance.infrastructure.security.CallerContextResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Resolves the caller once per request from the authenticated JWT. */
@Component
public class Callers {
  private final CallerContextResolver resolver;

  public Callers(CallerContextResolver resolver) {
    this.resolver = resolver;
  }

  public CallerContext of(Authentication authentication) {
    return resolver.resolve(authentication);
  }
}

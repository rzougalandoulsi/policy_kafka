package com.itland.governance.infrastructure.security;

import com.itland.governance.configuration.GovernanceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * OPA pulls bundles with a static bearer token (service credentials in its config). This filter guards
 * /internal/opa/** with that shared secret; the endpoint must additionally be kept off the public network.
 */
@Component
public class BundleTokenFilter implements WebFilter {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BundleTokenFilter.class);

  private final String expected;

  public BundleTokenFilter(GovernanceProperties properties) {
    this.expected = properties.opa().bundleToken();
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (!path.startsWith("/internal/opa/")) {
      return chain.filter(exchange);
    }
    String header = exchange.getRequest().getHeaders().getFirst("Authorization");
    if (expected == null || expected.isBlank() || header == null || !header.startsWith("Bearer ")
        || !MessageDigest.isEqual(header.substring(7).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
      log.warn("Rejected bundle request for {} (missing or invalid bearer token)", path);
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return exchange.getResponse().setComplete();
    }
    return chain.filter(exchange);
  }
}

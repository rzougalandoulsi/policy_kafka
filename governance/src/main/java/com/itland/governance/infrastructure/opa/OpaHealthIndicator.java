package com.itland.governance.infrastructure.opa;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("opa")
public class OpaHealthIndicator implements ReactiveHealthIndicator {

  private final OpaClient client;

  public OpaHealthIndicator(OpaClient client) {
    this.client = client;
  }

  @Override
  public Mono<Health> health() {
    return client.healthy().flatMap(ok -> ok
        ? client.loadedRevision().map(rev -> Health.up().withDetail("bundleRevision", rev).build())
            .onErrorResume(e -> Mono.just(Health.up().withDetail("bundleRevision", "unknown").build()))
        : Mono.just(Health.down().withDetail("reason", "OPA health endpoint not OK").build()));
  }
}

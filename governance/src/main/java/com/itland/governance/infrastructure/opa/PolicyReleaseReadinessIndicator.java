package com.itland.governance.infrastructure.opa;

import com.itland.governance.application.policy.PolicyBundleService;
import com.itland.governance.configuration.Blocking;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Readiness on the policy set, not on OPA's port: UP only when every required bundle OPA runs has exactly the revision
 * of the ACTIVE release and the composition reports ready. A replica with a missing, stale or incompatible bundle
 * therefore receives no traffic while it still answers liveness.
 */
@Component("policyRelease")
public class PolicyReleaseReadinessIndicator implements ReactiveHealthIndicator {

  private final PolicyBundleService service;
  private final Blocking blocking;

  public PolicyReleaseReadinessIndicator(PolicyBundleService service, Blocking blocking) {
    this.service = service;
    this.blocking = blocking;
  }

  @Override
  public Mono<Health> health() {
    return blocking.call(service::diagnostics)
        .map(d -> {
          Health.Builder b = d.match() && d.ready() ? Health.up() : Health.down();
          return b.withDetail("releaseId", d.releaseId() == null ? "none" : d.releaseId())
              .withDetail("match", d.match()).withDetail("ready", d.ready()).withDetail("bundles", d.bundles()).build();
        })
        .onErrorResume(e -> Mono.just(Health.down().withDetail("reason", e.toString()).build()));
  }
}

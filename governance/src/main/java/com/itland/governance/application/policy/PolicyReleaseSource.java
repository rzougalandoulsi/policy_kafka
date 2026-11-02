package com.itland.governance.application.policy;

import com.itland.governance.domain.policy.PolicyRelease;

import java.util.Optional;

/**
 * Where Governance learns which policy release OPA must run.
 * <ul>
 *   <li>{@code local}: the ACTIVE version per bundle in the database; Governance also serves those bundles to OPA and
 *       the admin API can upload, activate and roll back (developer stacks, the end-to-end test).</li>
 *   <li>{@code release}: an immutable release manifest mounted from the deployment (ConfigMap rendered from
 *       {@code releases/<env>.json}); OPA pulls the pinned revisions from Artifactory and nothing in Governance can
 *       change the set. Upload, activation and rollback answer 409 {@code POLICY_SOURCE_IS_GIT}.</li>
 * </ul>
 * In both modes every decision verifies the full set and readiness is DOWN until OPA runs exactly it.
 */
public interface PolicyReleaseSource {

  String LOCAL = "local";
  String RELEASE = "release";

  /** {@code local} or {@code release}. */
  String mode();

  /** Human-readable origin for diagnostics (for example {@code database} or {@code file:/etc/governance/release.json}). */
  String description();

  /** The expected release, empty when none is defined (local: nothing active; release: file missing or invalid). */
  Optional<PolicyRelease> expected();

  default boolean local() {
    return LOCAL.equals(mode());
  }
}

package com.itland.governance.application.policy;

import com.itland.governance.domain.policy.PolicyBundleVersion;
import com.itland.governance.domain.policy.PolicyRelease;
import com.itland.governance.infrastructure.persistence.PolicyBundleRepository;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Local mode: the release is the ACTIVE version of every bundle name in the database. */
public final class LocalPolicyReleaseSource implements PolicyReleaseSource {

  private final PolicyBundleRepository repository;

  public LocalPolicyReleaseSource(PolicyBundleRepository repository) {
    this.repository = repository;
  }

  @Override
  public String mode() {
    return LOCAL;
  }

  @Override
  public String description() {
    return "database (ACTIVE bundle versions)";
  }

  @Override
  public Optional<PolicyRelease> expected() {
    Map<String, String> revisions = new TreeMap<>();
    for (PolicyBundleVersion v : repository.findActiveAll()) revisions.put(v.bundleName(), v.revision());
    return revisions.isEmpty() ? Optional.empty() : Optional.of(PolicyRelease.of(revisions));
  }
}

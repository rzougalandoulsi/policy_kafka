package com.itland.governance.configuration;

import com.itland.governance.application.policy.FilePolicyReleaseSource;
import com.itland.governance.application.policy.LocalPolicyReleaseSource;
import com.itland.governance.application.policy.PolicyReleaseSource;
import com.itland.governance.infrastructure.persistence.PolicyBundleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;

/**
 * Chooses the policy release source from {@code governance.opa.mode} and enforces the deployment guardrail: a build
 * started with the {@code openshift} profile refuses to run in {@code local} mode, so bundle uploads and local
 * activation can never exist in a deployed environment whatever the configuration says.
 */
@Configuration
public class PolicyReleaseSourceConfig {

  private static final Logger log = LoggerFactory.getLogger(PolicyReleaseSourceConfig.class);
  static final String DEPLOYED_PROFILE = "openshift";

  @Bean
  public PolicyReleaseSource policyReleaseSource(GovernanceProperties properties, PolicyBundleRepository repository, Json json, Environment environment) {
    return create(properties.opa(), repository, json, environment.acceptsProfiles(Profiles.of(DEPLOYED_PROFILE)));
  }

  public static PolicyReleaseSource create(GovernanceProperties.Opa opa, PolicyBundleRepository repository, Json json, boolean deployedProfile) {
    String mode = opa.mode() == null ? PolicyReleaseSource.LOCAL : opa.mode().trim().toLowerCase();
    switch (mode) {
      case PolicyReleaseSource.LOCAL -> {
        if (deployedProfile) {
          throw new IllegalStateException("governance.opa.mode=local is not allowed with the '" + DEPLOYED_PROFILE
              + "' profile: deployed environments take their policy release from a mounted release manifest (mode=release)");
        }
        log.info("Policy release source: local (ACTIVE bundle versions in the database, bundles served to OPA by Governance)");
        return new LocalPolicyReleaseSource(repository);
      }
      case PolicyReleaseSource.RELEASE -> {
        if (opa.releaseFile() == null || opa.releaseFile().isBlank()) {
          throw new IllegalStateException("governance.opa.mode=release requires governance.opa.release-file (the mounted release manifest)");
        }
        Path file = Path.of(opa.releaseFile());
        log.info("Policy release source: release manifest {} (OPA pulls pinned revisions; local policy administration is disabled)", file);
        return new FilePolicyReleaseSource(file, json);
      }
      default -> throw new IllegalStateException("governance.opa.mode must be 'local' or 'release', got '" + opa.mode() + "'");
    }
  }
}

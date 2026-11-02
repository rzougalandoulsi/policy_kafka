package com.itland.governance.application.policy;

/** Raised by local-only policy administration (upload, activate, roll back, serve) when Governance runs in release mode. */
public class PolicySourceIsGitException extends RuntimeException {
  public PolicySourceIsGitException(String operation, String source) {
    super("Policy bundles are not managed through this API in release mode (" + operation + " refused): the release comes from "
        + source + "; change it by promoting a release in the governance-policies repository");
  }
}

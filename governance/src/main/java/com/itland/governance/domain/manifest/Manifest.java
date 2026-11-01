package com.itland.governance.domain.manifest;

import java.util.List;
import java.util.Map;

/** Complete desired state owned by one repository in one environment (the public API shape). */
public record Manifest(String teamId, String repositoryId, String environment, Resources resources) {

  public record Resources(List<TopicResource> topics, List<RbacResource> rbac, List<IdentityPoolResource> identityPools) {
    public Resources {
      topics = topics == null ? List.of() : List.copyOf(topics);
      rbac = rbac == null ? List.of() : List.copyOf(rbac);
      identityPools = identityPools == null ? List.of() : List.copyOf(identityPools);
    }
  }

  public record TopicResource(String name, Integer partitions, Long retentionMs, Map<String, String> config) {
    public TopicResource {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  public record RbacResource(String principal, String role, String resource) {
    public RbacResource {
      resource = resource == null ? "" : resource;
    }
  }

  public record IdentityPoolResource(String name, List<String> principals, List<RbacResource> rbac) {
    public IdentityPoolResource {
      principals = principals == null ? List.of() : List.copyOf(principals);
      rbac = rbac == null ? List.of() : List.copyOf(rbac);
    }
  }
}

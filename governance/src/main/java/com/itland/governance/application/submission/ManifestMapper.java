package com.itland.governance.application.submission;

import com.itland.governance.domain.manifest.Manifest;
import com.itland.orchestrator.avro.PoolSpec;
import com.itland.orchestrator.avro.RbacSpec;
import com.itland.orchestrator.avro.RepoDesired;
import com.itland.orchestrator.avro.TopicSpec;
import com.itland.platform.contracts.Digests;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the public manifest shape onto the Orchestrator's RepoDesired contract (one-to-one, no parallel model). */
public final class ManifestMapper {

  private ManifestMapper() {}

  public static RepoDesired toAvro(Manifest m) {
    return RepoDesired.newBuilder()
        .setTeamId(m.teamId())
        .setRepoId(m.repositoryId())
        .setEnvironment(m.environment())
        .setTopics(m.resources().topics().stream().map(t -> {
          Map<String, String> config = new LinkedHashMap<>(t.config());
          if (t.retentionMs() != null) config.put("retention.ms", String.valueOf(t.retentionMs()));
          return TopicSpec.newBuilder().setName(t.name()).setPartitions(t.partitions()).setConfig(config).build();
        }).toList())
        .setIdentityPools(m.resources().identityPools().stream().map(p -> PoolSpec.newBuilder()
            .setName(p.name()).setPrincipals(p.principals())
            .setRbacs(p.rbac().stream().map(ManifestMapper::rbac).toList()).build()).toList())
        .setRbacs(m.resources().rbac().stream().map(ManifestMapper::rbac).toList())
        .build();
  }

  public static String digest(Manifest m) {
    return Digests.manifestDigest(toAvro(m));
  }

  private static RbacSpec rbac(Manifest.RbacResource r) {
    return RbacSpec.newBuilder().setPrincipal(r.principal()).setResource(r.resource()).setRole(r.role()).build();
  }
}

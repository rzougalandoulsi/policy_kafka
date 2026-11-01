package com.itland.governance.application.usage;

import com.itland.governance.domain.decision.GovernanceStage;
import com.itland.governance.domain.manifest.Manifest;
import com.itland.governance.domain.request.ManifestRequest;
import com.itland.governance.domain.request.ProposedPlan;
import com.itland.governance.domain.usage.QuotaReservation;
import com.itland.governance.domain.usage.RepositoryUsage;
import com.itland.governance.domain.usage.UsageCounts;
import com.itland.governance.domain.usage.UsageSnapshot;
import com.itland.governance.infrastructure.persistence.QuotaReservationRepository;
import com.itland.governance.infrastructure.persistence.RepositoryUsageRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/**
 * Projects the usage snapshot one evaluation sees ({@code input.usage}, {@code input.ownership}, {@code input.repository}):
 * confirmed usage of every repository of the team in the environment, reservations of other in-flight plans, the delta
 * of this request, and the ownership conflicts of the resources it names. Blocking; runs inside the caller's transaction
 * when there is one, so a team row lock taken before it serialises the read with concurrent admissions.
 */
@Component
public class UsageProjector {

  private final RepositoryUsageRepository usage;
  private final QuotaReservationRepository reservations;

  public UsageProjector(RepositoryUsageRepository usage, QuotaReservationRepository reservations) {
    this.usage = usage;
    this.reservations = reservations;
  }

  public UsageSnapshot project(ManifestRequest request, GovernanceStage stage, ProposedPlan plan, Instant now) {
    String team = request.teamId();
    String env = request.environment();
    List<RepositoryUsage> repos = usage.findByTeam(team, env);

    Map<String, UsageCounts> byRepository = new TreeMap<>();
    Map<String, Long> asOf = new TreeMap<>();
    UsageCounts current = UsageCounts.ZERO;
    RepositoryUsage thisRepo = null;
    Instant oldest = null;
    for (RepositoryUsage r : repos) {
      UsageCounts counts = UsageMath.countsOf(r);
      byRepository.put(r.repositoryId(), counts);
      current = current.plus(counts);
      if (r.hasSnapshot()) {
        asOf.put(r.repositoryId(), r.stateVersion());
        if (r.snapshotAt() != null && (oldest == null || r.snapshotAt().isBefore(oldest))) oldest = r.snapshotAt();
      }
      if (r.repositoryId().equals(request.repositoryId())) thisRepo = r;
    }

    UsageCounts reserved = UsageCounts.ZERO;
    for (QuotaReservation r : reservations.findOpen(team, env, request.id(), now)) reserved = reserved.plus(r.deltas());

    UsageCounts delta = stage == GovernanceStage.REQUEST || plan == null
        ? UsageMath.manifestDelta(request.manifest(), thisRepo)
        : UsageMath.actionsDelta(plan.actions(), thisRepo);

    Map<String, String> conflicts = ownershipConflicts(request, stage, plan);
    boolean ownedByTeam = !usage.ownedByAnotherTeam(team, request.repositoryId(), env);
    OptionalLong repoVersion = thisRepo != null && thisRepo.hasSnapshot() ? OptionalLong.of(thisRepo.stateVersion()) : OptionalLong.empty();
    Duration age = oldest == null ? null : Duration.between(oldest, now);
    return new UsageSnapshot(current, reserved, delta, byRepository, asOf, age, conflicts, ownedByTeam, repoVersion);
  }

  /** "TYPE:name" -> owner for topics and identity pools this request names that another repository's confirmed state holds. */
  private Map<String, String> ownershipConflicts(ManifestRequest request, GovernanceStage stage, ProposedPlan plan) {
    Set<String> topics = new LinkedHashSet<>();
    Set<String> pools = new LinkedHashSet<>();
    if (stage == GovernanceStage.REQUEST || plan == null) {
      Manifest m = request.manifest();
      m.resources().topics().forEach(t -> topics.add(t.name()));
      m.resources().identityPools().forEach(p -> pools.add(p.name()));
    } else {
      for (ProposedPlan.PlannedActionView a : plan.actions()) {
        if ("TOPIC_CREATE".equals(a.actionType())) topics.add(a.resourceName());
        if ("IDENTITY_POOL_CREATE".equals(a.actionType())) pools.add(a.resourceName());
      }
    }
    Map<String, String> out = new TreeMap<>();
    for (RepositoryUsage owner : usage.findOwners(request.environment(), topics, pools)) {
      if (owner.teamId().equals(request.teamId()) && owner.repositoryId().equals(request.repositoryId())) continue;
      String label = owner.teamId().equals(request.teamId()) ? owner.repositoryId() : owner.teamId() + "/" + owner.repositoryId();
      for (String t : owner.topics()) if (topics.contains(t)) out.putIfAbsent("TOPIC:" + t, label);
      for (String p : owner.identityPools()) if (pools.contains(p)) out.putIfAbsent("IDENTITY_POOL:" + p, label);
    }
    return out;
  }
}

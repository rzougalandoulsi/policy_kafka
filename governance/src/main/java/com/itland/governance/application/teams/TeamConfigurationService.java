package com.itland.governance.application.teams;

import com.itland.governance.application.audit.AuditService;
import com.itland.governance.domain.team.TeamConfiguration;
import com.itland.governance.domain.team.TeamConfigurationChange;
import com.itland.governance.domain.usage.QuotaReservation;
import com.itland.governance.domain.usage.RepositoryUsage;
import com.itland.governance.domain.usage.UsageCounts;
import com.itland.governance.application.usage.UsageMath;
import com.itland.governance.infrastructure.persistence.PolicyConfigurationRepository;
import com.itland.governance.infrastructure.persistence.QuotaReservationRepository;
import com.itland.governance.infrastructure.persistence.RepositoryUsageRepository;
import com.itland.governance.infrastructure.persistence.TeamConfigurationRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Team configuration: authorised prefixes, quotas, allowed-role override, environment parameters. Governance is the
 * system of record; a row marked {@code source = external} is owned by an enterprise registry and refuses edits unless
 * the admin explicitly takes ownership back ({@code source = governance}). Every change bumps the version, appends to
 * the history table and is audited. All methods are blocking (worker threads / blocking scheduler).
 */
@Service
public class TeamConfigurationService {

  private static final Pattern TEAM_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
  private static final int MAX_PREFIXES = 50;

  /** The team is owned by an external registry; edits must come through the sync or explicitly take ownership. */
  public static class ExternallyManagedException extends IllegalStateException {
    public ExternallyManagedException(String message) { super(message); }
  }

  public record UpsertCommand(List<String> authorizedPrefixes, Map<String, Long> quotas, List<String> allowedRoles,
                              Map<String, Object> environments, String source, String sourceVersion, String comment) {}

  public record RepositoryUsageView(String repositoryId, String environment, long stateVersion, Map<String, Long> counts,
                                    List<String> topics, List<String> identityPools, List<String> rbacBindings, Instant snapshotAt) {}

  public record ReservationView(String id, String environment, String requestId, String planId, Map<String, Long> deltas, Instant createdAt, Instant expiresAt) {}

  public record TeamUsage(String teamId, Map<String, Map<String, Long>> currentByEnvironment, Map<String, Map<String, Long>> reservedByEnvironment,
                          List<RepositoryUsageView> repositories, List<ReservationView> reservations) {}

  private final TeamConfigurationRepository repository;
  private final PolicyConfigurationRepository configurations;
  private final RepositoryUsageRepository usage;
  private final QuotaReservationRepository reservations;
  private final AuditService audit;
  private final Clock clock;

  public TeamConfigurationService(TeamConfigurationRepository repository, PolicyConfigurationRepository configurations, RepositoryUsageRepository usage,
                                  QuotaReservationRepository reservations, AuditService audit, Clock clock) {
    this.repository = repository;
    this.configurations = configurations;
    this.usage = usage;
    this.reservations = reservations;
    this.audit = audit;
    this.clock = clock;
  }

  public List<TeamConfiguration> list() {
    return repository.list();
  }

  /** Stored configuration or the defaults the team evaluates against (source "default", version 0). */
  public TeamConfiguration get(String teamId) {
    return repository.findById(teamId).orElseGet(() -> TeamConfiguration.defaults(teamId, activeSeparator()));
  }

  /** Same as {@link #get} but with the separator taken from the configuration snapshot of the current evaluation. */
  public TeamConfiguration effective(String teamId, Map<String, Object> policyConfiguration) {
    return repository.findById(teamId).orElseGet(() -> TeamConfiguration.defaults(teamId, separatorOf(policyConfiguration)));
  }

  public TeamConfiguration upsert(String teamId, UpsertCommand c, String actor) {
    if (teamId == null || !TEAM_ID.matcher(teamId).matches()) throw new IllegalArgumentException("teamId must match " + TEAM_ID);
    TeamConfiguration candidate = validate(teamId, c, actor);
    TeamConfiguration existing = repository.findById(teamId).orElse(null);
    if (existing != null && existing.external() && candidate.external()) {
      throw new ExternallyManagedException("Team " + teamId + " is managed by an external registry (" + existing.sourceVersion()
          + "); set source=governance to take ownership before editing");
    }
    Instant now = clock.instant();
    TeamConfiguration stored = repository.upsert(new TeamConfiguration(teamId, 0, candidate.authorizedPrefixes(), candidate.quotas(), candidate.allowedRoles(),
        candidate.environments(), candidate.source(), candidate.sourceVersion(), candidate.external() ? now : null, c.comment(), actor, now, now), now);
    String action = existing == null ? "CREATED" : "UPDATED";
    repository.recordChange(teamId, stored.version(), stored.content(), action, actor, now, c.comment());
    Map<String, Object> details = new LinkedHashMap<>(stored.content());
    details.put("version", stored.version());
    details.put("comment", c.comment() == null ? "" : c.comment());
    if (existing != null && existing.external() && !candidate.external()) details.put("ownershipTakenFrom", String.valueOf(existing.sourceVersion()));
    audit.record(actor, "TEAM_CONFIGURATION_" + action, "team_configuration", teamId, details);
    return stored;
  }

  /** Removes the stored configuration; the team falls back to defaults. Returns those defaults. */
  public TeamConfiguration delete(String teamId, String actor) {
    TeamConfiguration existing = repository.findById(teamId).orElse(null);
    if (existing != null) {
      Instant now = clock.instant();
      repository.delete(teamId);
      repository.recordChange(teamId, existing.version() + 1, existing.content(), "DELETED", actor, now, null);
      audit.record(actor, "TEAM_CONFIGURATION_DELETED", "team_configuration", teamId, Map.of("version", existing.version()));
    }
    return get(teamId);
  }

  public List<TeamConfigurationChange> history(String teamId) {
    return repository.history(teamId, 200);
  }

  public TeamUsage usage(String teamId) {
    Instant now = clock.instant();
    Map<String, Map<String, Long>> currentByEnv = new TreeMap<>();
    List<RepositoryUsageView> repos = new ArrayList<>();
    for (RepositoryUsage u : usage.findByTeam(teamId)) {
      UsageCounts counts = UsageMath.countsOf(u);
      repos.add(new RepositoryUsageView(u.repositoryId(), u.environment(), u.stateVersion(), counts.sorted(), u.topics(), u.identityPools(), u.rbacBindings(), u.snapshotAt()));
      UsageCounts sum = new UsageCounts(currentByEnv.getOrDefault(u.environment(), Map.of())).plus(counts);
      currentByEnv.put(u.environment(), sum.sorted());
    }
    Map<String, Map<String, Long>> reservedByEnv = new TreeMap<>();
    List<ReservationView> open = new ArrayList<>();
    for (QuotaReservation r : reservations.findOpenByTeam(teamId, now)) {
      open.add(new ReservationView(r.id(), r.environment(), r.requestId(), r.planId(), r.deltas().sorted(), r.createdAt(), r.expiresAt()));
      UsageCounts sum = new UsageCounts(reservedByEnv.getOrDefault(r.environment(), Map.of())).plus(r.deltas());
      reservedByEnv.put(r.environment(), sum.sorted());
    }
    return new TeamUsage(teamId, currentByEnv, reservedByEnv, repos, open);
  }

  static TeamConfiguration validate(String teamId, UpsertCommand c, String actor) {
    if (c == null) throw new IllegalArgumentException("body is required");
    Set<String> prefixes = new LinkedHashSet<>();
    for (String p : c.authorizedPrefixes() == null ? List.<String>of() : c.authorizedPrefixes()) {
      if (p == null || p.isBlank() || p.length() > 128 || p.chars().anyMatch(Character::isWhitespace)) {
        throw new IllegalArgumentException("authorizedPrefixes entries must be non-blank, without whitespace, at most 128 characters");
      }
      prefixes.add(p.toLowerCase(Locale.ROOT));
    }
    if (prefixes.size() > MAX_PREFIXES) throw new IllegalArgumentException("at most " + MAX_PREFIXES + " authorised prefixes");
    Map<String, Long> quotas = new TreeMap<>();
    if (c.quotas() != null) {
      for (Map.Entry<String, Long> e : c.quotas().entrySet()) {
        if (!TeamConfiguration.QUOTA_KEYS.contains(e.getKey())) throw new IllegalArgumentException("unknown quota key '" + e.getKey() + "'; allowed: " + TeamConfiguration.QUOTA_KEYS);
        if (e.getValue() == null || e.getValue() < 0) throw new IllegalArgumentException("quotas." + e.getKey() + " must be a non-negative integer");
        quotas.put(e.getKey(), e.getValue());
      }
    }
    List<String> roles = new ArrayList<>();
    for (String r : c.allowedRoles() == null ? List.<String>of() : c.allowedRoles()) {
      if (r == null || r.isBlank()) throw new IllegalArgumentException("allowedRoles entries must be non-blank");
      if (!roles.contains(r)) roles.add(r);
    }
    String source = c.source() == null || c.source().isBlank() ? TeamConfiguration.SOURCE_GOVERNANCE : c.source();
    if (!TeamConfiguration.SOURCES.contains(source)) throw new IllegalArgumentException("source must be one of " + TeamConfiguration.SOURCES);
    if (TeamConfiguration.SOURCE_EXTERNAL.equals(source) && (c.sourceVersion() == null || c.sourceVersion().isBlank())) {
      throw new IllegalArgumentException("sourceVersion is required when source is external");
    }
    return new TeamConfiguration(teamId, 0, List.copyOf(prefixes), quotas, roles, c.environments() == null ? Map.of() : c.environments(),
        source, TeamConfiguration.SOURCE_EXTERNAL.equals(source) ? c.sourceVersion() : null, null, c.comment(), actor, null, null);
  }

  private String activeSeparator() {
    return configurations.findActive().map(cfg -> separatorOf(cfg.content())).orElse(".");
  }

  @SuppressWarnings("unchecked")
  static String separatorOf(Map<String, Object> policyConfiguration) {
    Object naming = policyConfiguration == null ? null : policyConfiguration.get("naming");
    if (naming instanceof Map<?, ?> n && n.get("teamPrefixSeparator") instanceof String s) return s;
    return ".";
  }
}

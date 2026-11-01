package com.itland.governance.application.teams;

import com.itland.governance.domain.team.TeamConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamConfigurationServiceTest {

  private static TeamConfigurationService.UpsertCommand command(List<String> prefixes, Map<String, Long> quotas, String source, String sourceVersion) {
    return new TeamConfigurationService.UpsertCommand(prefixes, quotas, List.of("DeveloperRead", "DeveloperRead"), Map.of(), source, sourceVersion, "why");
  }

  @Test
  void normalisesPrefixesAndRoles() {
    TeamConfiguration c = TeamConfigurationService.validate("team-a", command(List.of("Team-A.", "orders.", "team-a."), Map.of("topics", 5L), null, null), "admin-1");
    assertThat(c.authorizedPrefixes()).containsExactly("team-a.", "orders.");
    assertThat(c.allowedRoles()).containsExactly("DeveloperRead");
    assertThat(c.quotas()).containsEntry("topics", 5L);
    assertThat(c.source()).isEqualTo(TeamConfiguration.SOURCE_GOVERNANCE);
    assertThat(c.sourceVersion()).isNull();
  }

  @Test
  void rejectsUnknownQuotaKeysNegativeValuesAndBlankPrefixes() {
    assertThatThrownBy(() -> TeamConfigurationService.validate("team-a", command(List.of("x."), Map.of("partitions", 1L), null, null), "a"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown quota key");
    assertThatThrownBy(() -> TeamConfigurationService.validate("team-a", command(List.of("x."), Map.of("topics", -1L), null, null), "a"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
    assertThatThrownBy(() -> TeamConfigurationService.validate("team-a", command(List.of("has space."), Map.of(), null, null), "a"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authorizedPrefixes");
  }

  @Test
  void externalSourceNeedsAVersion() {
    assertThatThrownBy(() -> TeamConfigurationService.validate("team-a", command(List.of("x."), Map.of(), "external", null), "a"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sourceVersion");
    assertThat(TeamConfigurationService.validate("team-a", command(List.of("x."), Map.of(), "external", "registry-42"), "a").external()).isTrue();
    assertThatThrownBy(() -> TeamConfigurationService.validate("team-a", command(List.of("x."), Map.of(), "ldap", null), "a"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
  }

  @Test
  void defaultsUseTheTeamIdAndTheConfiguredSeparator() {
    TeamConfiguration d = TeamConfiguration.defaults("Team-A", "-");
    assertThat(d.authorizedPrefixes()).containsExactly("team-a-");
    assertThat(d.stored()).isFalse();
    assertThat(d.version()).isZero();
    assertThat(TeamConfigurationService.separatorOf(Map.of("naming", Map.of("teamPrefixSeparator", "_")))).isEqualTo("_");
    assertThat(TeamConfigurationService.separatorOf(Map.of())).isEqualTo(".");
  }
}

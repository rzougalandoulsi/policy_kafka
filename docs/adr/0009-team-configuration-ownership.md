# ADR 0009: Governance is the system of record for team configuration

## Context
Input contract 2.0 moves per-team parameters out of policy code and out of the global policy configuration: authorised
topic prefixes (several per team instead of `teamId + separator`), quotas per resource type across all repositories,
allowed-role overrides and free-form environment parameters. Somebody has to own that data. No enterprise team registry
was confirmed (plan assumption 4).

## Decision
- `team_configuration` in Governance is the system of record. One row per team, `version` bumped on every change, the
  previous content appended to `team_configuration_history`, and an audit record (`TEAM_CONFIGURATION_CREATED|UPDATED|DELETED`).
  Admin API `/api/v1/admin/teams` (auditor reads, admin writes), admin console pages `/teams` and `/teams/{id}`.
- A team without a row evaluates against defaults: one prefix `<teamId><naming.teamPrefixSeparator>`, no quotas,
  `source = default`, version 0. The API returns those defaults rather than 404 so the console can explain what applies.
- Every row carries `source`. `governance` is editable. `external` marks data owned by an enterprise registry
  (`sourceVersion`, `syncedAt`): the API refuses edits with 409 `TEAM_CONFIGURATION_EXTERNAL` unless the admin saves
  with `source = governance`, which takes ownership back and is audited. No sync job exists yet; when a registry is
  confirmed it writes through the same service and sets `source = external`.
- The configuration is read on every evaluation (no cache), so a change applies to the next request without a restart.
  Its `version` travels to OPA as `input.team.configurationVersion`.

## Consequences
- The old `TEAM_PREFIX_REQUIRED` policy is replaced by `AUTHORIZED_PREFIX_REQUIRED` over `input.team.authorizedPrefixes`;
  exceptions scoped to the old id must be re-created.
- Prefixes are stored lower-cased and matched case-insensitively.

## Update 2026-11-02 (Phase 8 review)
Implemented as decided. No enterprise team registry has been confirmed (plan assumption 4), so no sync job exists and every row is `source = governance` or absent (`default`). The decision is unchanged; it will be revisited only when a registry owner is named. The admin console's Teams pages and `GET /api/v1/admin/teams/{id}/usage` are the operational surface (`docs/runbook.md`).

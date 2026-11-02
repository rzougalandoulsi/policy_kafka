# Bundle authoring guide

How to change or add a policy in `governance-policies` so that Workflow A accepts it, Workflow B can build it, and
Governance keeps verifying the result. Read `README.md` first for the layout; this guide is the "how" behind the rules
the checks enforce.

## 1. Where a rule lives

| Concern | Bundle | Package(s) | Typical rules |
|---|---|---|---|
| composition, global prohibitions, exception matching | `core` | `governance.core`, `.authz`, `.exceptions`, `.lib` | `RBAC_ADMIN_ROLE_FORBIDDEN` (HARD_DENY) |
| repository ownership, cross-repository conflicts | `manifests` | `governance.manifests` | `RESOURCE_OWNED_BY_OTHER_REPOSITORY`, `REPOSITORY_OWNED_BY_OTHER_TEAM` |
| names and prefixes | `naming` | `governance.naming` | `TOPIC_NAME_PATTERN`, `AUTHORIZED_PREFIX_REQUIRED`, `ENV_NAME_RESTRICTION` |
| team quotas and plan-level limits | `quotas` | `governance.quotas` | `TEAM_QUOTA_EXCEEDED`, `MAX_PARTITIONS_PER_PLAN` |
| per-resource restrictions and roles | `resources` | `governance.resources`, `.rbac`, `.limits` | `RBAC_*_ALLOWED`, `MAX_*`, `TOPIC_RETENTION_LIMIT` |
| destructive changes and approvals | `changes` | `governance.changes`, `.destructive`, `.approval` | the `*_RESTRICTED` plan rules, the `*_REQUIRES_APPROVAL` rules |

A rule goes into the bundle whose owners (`.github/CODEOWNERS`) should review it and whose release cadence it should
share. Moving a rule between bundles is a behaviour-neutral change only if its policy id, severity and `bypassable`
flag stay the same (the catalogue test checks the whole set).

## 2. Roots and packages

- One bundle is one directory `bundles/<name>/` with `.manifest` `{"roots": ["governance/<name>"], "rego_version": 1}`.
  Every package in it is `governance.<name>` or `governance.<name>.<sub>`. `scripts/check-roots.sh` fails on any other
  package, on a root that is a prefix of another, and on a bundle set that is not exactly the six names.
- **No cross-bundle `import`.** Rego functions do not resolve across bundles when each bundle is compiled on its own
  (`opa build -b bundles/<name>`), so a bundle may only import its own packages. `core` reaches the domain bundles by
  dynamic reference (`data.governance[ns].violations`), never by import.
- **`lib.rego` is copied.** Every bundle carries the same helpers under its own package
  (`governance.<name>.lib`). Edit `bundles/core/lib.rego`, then copy it to the other five and fix the first line
  (`package governance.<name>.lib`); `scripts/check-lib-sync.sh` compares everything after the package line.
- `core` must not define rules named `violations`, `approvals` or `catalog`: `data.governance[ns].<name>` would then
  refer back to core and OPA rejects the composition as recursive. Core exposes `decision`, `ready`, `policy_catalog`.

## 3. The bundle contract (what core reads)

Every domain bundle exposes, at `governance.<name>`:

| Rule | Type | Meaning |
|---|---|---|
| `violations` | set of violation objects | built with `h.violation(policy_id, severity, msg, resource_type, resource_name, bypassable)` |
| `approvals` | set of approval objects (`set()` when the bundle has none) | built with `h.approval(policy_id, msg, resource_type, resource_name)` |
| `catalog` | array of catalogue entries | one per policy id the bundle can emit |
| `contract_version` | string `"MAJOR.MINOR"` | the input/output contract the bundle is written against (`docs/contract.md`) |

Core computes `ready` (every required bundle loaded and its major equal to core's), merges the violations with the
exceptions Governance resolved, applies the precedence `HARD_DENY > DENY > REQUIRE_APPROVAL > ALLOW`, drops shadow
findings from the outcome and returns `decision`. A rule that emits something outside this contract (a new top-level
rule core should read) is a contract change and needs an entrypoint in `scripts/build-release.sh` (`-e`), because
`opa build -O1` prunes rules unreachable from the entrypoints.

## 4. Writing a rule

```rego
package governance.naming

import data.governance.naming.lib as h

# POLICY_ID: one line that says what is checked (bypassable or not).
violations contains h.violation("POLICY_ID", "DENY", msg, "TOPIC", t.name, true) if {
	h.stage_request                       # or h.stage_plan; rules that apply to both omit the guard
	some t in input.manifest.topics
	not regex.match(h.config.naming.topicPattern, t.name)
	msg := sprintf("topic '%s' does not match pattern %s", [t.name, h.config.naming.topicPattern])
}
```

Conventions the checks and the reviewers expect:

- **Helpers only through `h`**: stage guards (`h.stage_request`, `h.stage_plan`), configuration (`h.config.<section>`),
  team data (`h.team_prefixes`, `h.has_authorized_prefix`, `input.team.quotas`, `input.team.allowedRoles`), usage
  (`h.projected(key)`, `h.usage_value(section, key)`), limits with environment overrides (`h.limit(name)`), bindings
  (`h.bindings`). `h.violation` and `h.approval` add `enforcement` (`enforce` or `shadow`) from
  `input.configuration.enforcement[policyId]`; never build these objects by hand.
- **Severity**: `DENY` for bypassable rules, `HARD_DENY` only for global prohibitions in `core.authz` (never
  exemptable, never approvable), approvals are `REQUIRE_APPROVAL` by construction.
- **Deterministic input only**: no `http.send`, no `opa.runtime`, no `time.now_ns` (`scripts/check-builtins.sh`;
  use `input.now`). Everything a rule needs is in `input` (Governance supplies it) or in the bundle's `data.json`
  (static reference data only, never team data).
- **Missing fields fail closed where it matters**: read optional input with `object.get(...)` and a safe default;
  a missing `enforcement` field is treated as `enforce` by core on purpose.
- **Order of expressions** does not change semantics in Rego, but Regal asks for expressions that do not depend on the
  loop variable to come before the `some ... in` that iterates (performance). Keep incremental rules of one name
  together (no other rule between two `violations contains` blocks).
- **Format and lint**: `scripts/opa.sh fmt --write bundles tests`, `scripts/lint.sh bundles tests`. The Regal baseline
  (`.regal/config.yaml`) is deliberately short; fix the Rego rather than adding an ignore, and when an ignore is
  unavoidable write the reason next to it.

## 5. Catalogue entry

Every policy id a bundle can emit has exactly one entry in its `catalog`:

```rego
{"policyId": "POLICY_ID", "bundle": "naming", "kind": "VIOLATION", "section": "naming", "severity": "DENY",
 "bypassable": true, "stages": ["REQUEST"], "resourceType": "TOPIC", "description": "What the rule checks and which configuration key drives it."}
```

- `kind` is `VIOLATION` or `APPROVAL`; `section` is one of `naming`, `rbac`, `limits`, `destructive`, `approvals`,
  `manifests` (the configuration sections the admin console knows); `bypassable` is `false` for every `HARD_DENY` and
  every `APPROVAL`; `stages` lists `REQUEST` and/or `ACTION_PLAN`.
- `tests/catalog_test.rego` checks uniqueness, bundle names, the `bypassable` rules, that everything a worst-case input
  emits is catalogued with the same severity and flag, and **the catalogue size**
  (`test_catalogue_has_the_expected_size`: 20 policies, 16 bypassable today). Bump those two numbers in the same pull
  request as the new policy.
- The merged catalogue (`data.governance.core.policy_catalog`) is what `GET /api/v1/admin/policies` relays to the admin
  console: the exception form offers only bypassable ids from it. Nothing in Governance Java needs to change for a new
  policy id; a new configuration key needs `PolicyConfigurationService.validate` (policy_kafka) and the console editor.

## 6. Tests

- Unit tests live in `tests/<bundle>_test.rego` (package `governance.<bundle>_test` or `governance.core.<x>_test`)
  and use the shared helpers of `tests/fixtures_test.rego` (`f.request(env, topics, rbac)`, `f.plan(env, actions)`,
  `f.topic`, `f.action`, `f.with_team`, `f.with_config`, `f.with_enforcement`, `f.usage`). Test the rule through the
  composed decision (`core.decision with input as ...`) at least once, and the negative case.
- Coverage threshold 85 % over `bundles/` (`COVERAGE_THRESHOLD`, Workflow A); `opa test` runs with all six bundles
  loaded, so cross-bundle breakage (a bundle that is no longer `ready`) is caught before any build.
- **Behaviour fixtures** (`tests/fixtures/inputs/<name>.json` + `tests/fixtures/expected/<name>.json`) are full
  contract-2.0 inputs with the decision they must produce (minus the runtime `revisions` map). They are evaluated by
  Workflow A against the sources, by Workflow B against the signed artifacts, and by Workflow D against every live
  sidecar, so they are the executable definition of "the release behaves". Add one when a change alters a decision
  outcome: write the input (it must validate against `contracts/input.schema.json`), run
  `scripts/eval-fixtures.sh --update`, **review the diff of `expected/`** and commit both files. Keep fixtures few
  and meaningful (eight today, one per outcome family). Never update expected files to make a failing check pass
  without understanding the change.

## 7. Rolling a new rule out

1. Open the pull request; Workflow A runs `scripts/validate.sh` (fmt, strict check, tests, coverage, roots, lib copies,
   builtins, contract sync, fixtures, schemas, Regal, gitleaks, dry-run build per bundle). Run the same script locally
   first; `tests/integration/validate-negative.sh` shows what each check rejects.
2. Merge; Workflow B publishes a candidate with the changed bundle(s) at new revisions and the others unchanged.
3. Soak before enforcing: in Governance, activate a policy configuration version with
   `enforcement: {"POLICY_ID": "shadow"}` in dev and staging; the rule is evaluated and recorded
   (`GET /api/v1/admin/shadow-findings`, metric `governance_shadow_findings_total`) but never blocks. Remove the entry
   to enforce. Shadow mode is a Governance-side switch; the bundle does not change.
4. Promote with Workflow C, verify with Workflow D (`docs/operations.md`).

## 8. Local loop

| Task | Command |
|---|---|
| everything Workflow A runs | `scripts/validate.sh` (`SKIP_LINT=1 SKIP_SECRETS=1` without Docker) |
| only the tests | `test.sh` |
| format / lint | `scripts/opa.sh fmt --write bundles tests`, `scripts/lint.sh bundles tests` |
| unsigned bundles + `dist/release.json` for Compose and the Governance IT | `build.sh [revision]` then `deploy/scripts/upload-release.sh` (policy_kafka) |
| what a fixture decides right now | `scripts/eval-fixtures.sh` (`--update` to rewrite `expected/`) |
| signed build like Workflow B | `scripts/build-release.sh --suffix <yyyyMMdd>.<run>-<sha> --signing-key key.pem [--key-id ID]` |
| the whole publish and promotion flow without an Artifactory | `tests/integration/publish-local.sh`, `tests/integration/promote-local.sh` |

Pitfalls met so far: six optimized bundles need `--partial-namespace governance/<bundle>/partial` and explicit
entrypoints (`build-release.sh` does it); a signed bundle cannot be loaded without a verification key, so the local flow
stays unsigned; `opa test` must ignore `tests/fixtures` and `tests/integration` or the JSON fixtures are merged as data;
`default ready := false` in core is required or the decision object is undefined when a bundle is missing.

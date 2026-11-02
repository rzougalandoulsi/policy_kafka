# Open work: Phases 6 and 7 of the OPA governance programme

Status on 2026-11-02: Phases 1 to 5 and 8 of `.claude/plans/opa-multi-bundle-platform.plan.md` are implemented and
committed. The two phases below are the only ones left, and both are **blocked on inputs from outside this
repository** (the Orchestrator and Processor owners). This page is the standing request to those owners and the
to-do list for whoever picks the phases up; nothing here can be started honestly without the inputs marked [A].

## Phase 6: resource-type expansion (about 2 weeks once unblocked)

**Today.** Manifests, Avro contracts, the Governance domain and the Rego cover topics, identity pools and RBAC
bindings. Input contract 2.0 already reserves empty arrays `schemas`, `serviceAccounts`, `connectors` and
`flinkStatements`, so adding the types is a contract **minor**, not a major.

**Scope.**
1. Additive Avro records `SchemaSpec{subject, format, schema?, compatibility?}`, `ServiceAccountSpec{name, description}`,
   `ConnectorSpec{name, class, config}`, `FlinkStatementSpec{name, statement, computePool}`; arrays with default `[]` in
   `RepoDesired` and `RepoConfirmed`; `Action.resourceType` extended if it is an enum.
2. A backward-compatibility test in `contracts/` against the previous git revision (Avro `SchemaCompatibility`),
   `BACKWARD` level per subject.
3. Governance: `ManifestValidator`, `OpaInputAssembler` (fill the arrays), `UsageProjector` (new quota keys),
   `RepositoryStateConsumer` (new resource names for ownership), the catalogue sections if needed.
4. Rego: naming conventions per type in `naming`, allowed roles and per-resource limits in `resources`, quota keys in
   `quotas`, destructive operations and approvals per type in `changes`; tests and at least one fixture per type.
5. Simulators carry the new arrays; an end-to-end IT scenario where a manifest with schemas and connectors completes.
6. Admin console: exception form resource types, catalogue display; `docs/kafka-contract.md`, the inventory page.

**Inputs needed [A], from the Orchestrator and Processor owners.**
| # | Question | Why it matters |
|---|---|---|
| 1 | Do you agree with the record shapes and array names above? Is `Action.resourceType` an enum, and which symbols does it take per type (create, update, removal)? | Avro additions must be backward compatible for both producers; the simulators and the Rego action rules key on the symbols. |
| 2 | Will the production `RepoConfirmed` carry topic partitions and the names of every resource type? | Partition quotas are approximate ("as last declared") until it does (ADR 0010). |
| 3 | Which Schema Registry compatibility level do the subjects use, and who owns `com.itland.platform.avro.*` and `com.itland.orchestrator.avro.*`? | The compatibility test and the registry setting must agree. |
| 4 | Per type: naming convention, allowed roles, what a quota counts (subjects, accounts, connector instances, statements), which operations are destructive, which need approval. | These become the rules, their catalogue entries and the default policy configuration. |
| 5 | Which Actions can the Processor execute per type, or where is its source? | The simulators must mirror them for the end-to-end scenario. |

## Phase 7: Orchestrator rule migration (depends on source access)

**Today.** The real Orchestrator loads naming conventions and allowed RBAC roles at startup and validates manifests
itself (`docs/repository-analysis.md`: the inspected prototype had no validation rules, the real one is not in this
repository). The migration machinery exists: shadow mode per policy id, `shadow_finding`, the metric
`governance_shadow_findings_total`, `GET /api/v1/admin/shadow-findings`, and `MANIFEST_REJECTED` after a Governance
`ALLOW` as the discrepancy signal.

**Scope.**
1. Fill the migration matrix (plan section 4.2): every Orchestrator rule classified A (stays technical there),
   B (moves to Rego), or C (split), with its configuration source.
2. Implement the missing Rego with tests and fixtures; regenerate the inventory page.
3. Soak in dev and staging with `configuration.enforcement[policyId] = shadow`; compare the shadow findings and the
   `MANIFEST_REJECTED`-after-`ALLOW` count over the agreed window until both are explained.
4. Flip stage 1 policies to enforce, then stage 2.
5. Remove the duplicated business rules from the Orchestrator (its repository); technical validations stay on both sides.

**Inputs needed [A].**
| # | Question | Why it matters |
|---|---|---|
| 1 | Read access to the Orchestrator repository, or an export of its validation classes and the startup-loaded configuration per environment (naming conventions, allowed RBAC roles). | The matrix cannot be filled from this repository; the prototype analysed had no rules. |
| 2 | The Processor source or documentation of what it rejects on its own. | Rejections it produces would otherwise look like Governance discrepancies during the soak. |
| 3 | Agreement on the A/B/C classification per rule and on the soak window per environment; access to the Orchestrator's rejection reasons. | Defines "no discrepancy" and when enforcement can be flipped. |
| 4 | An owner on the Orchestrator side to remove the duplicated rules after the soak. | Otherwise the rules stay duplicated and drift. |

## Platform inputs that gate the first real deployment of what already exists

Not Phase 6 or 7, but the same conversation: Artifactory URL, repository key, OIDC provider or token policy, private
CA; OpenShift API URL, namespaces, SCC confirmation, ingress and monitoring namespace selectors, External Secrets,
registry and its authentication; enterprise IdP issuer and client ids; GitHub organisation (CODEOWNERS teams,
protected environments, tokens); signing key custody. The ordered checklist is `policies/docs/extraction-checklist.md`.

## When picking this up

Read the five "Phase N delivery notes" blocks and the "Remaining work" block in the plan, then ADRs 0007 to 0010.
Everything verified so far and how to re-verify it is listed in the Phase 8 notes; the operating procedures are in
`docs/runbook.md`.

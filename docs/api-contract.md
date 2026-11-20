# Governance API contract

REST surface of the governance service, derived from the controllers under `governance/src/main/java/com/itland/governance/api` on 2026-11-19. The service also publishes the machine-readable contract at runtime through springdoc: `GET /v3/api-docs` (JSON) and `/swagger-ui.html`, both unauthenticated. This document is the human-readable companion; when the two disagree, the running service wins and this file needs an update.

Base path is `/api/v1`. All bodies and responses are JSON. Handlers are reactive (WebFlux) and return a single object or a plain array, never a page envelope.

## Authentication and roles

Every `/api/v1/**` route requires a bearer JWT validated against `governance.security.issuer-uri`. Identity and entitlements come only from the token, never from request bodies.

| Claim | Default name | Meaning |
|---|---|---|
| `sub` | `sub` | Principal id. Required; a blank subject is 403. |
| teams | `teams` | Team ids the caller belongs to. Array, or a string split on commas and whitespace. |
| environments | `environments` | Environments the caller may submit to. `*` means all. |
| roles | `roles` | Role names. Mapped to Spring authorities as `ROLE_` plus the upper-cased name with dashes replaced by underscores. |

| Role | Default value | Grants |
|---|---|---|
| admin | `governance-admin` | All write endpoints under `/admin`. Implies auditor. |
| approver | `governance-approver` | List and read all approvals, record verdicts, read any request. |
| auditor | `governance-auditor` | Read endpoints under `/admin`, list approvals, read any request. |
| team member | claim membership | Submit manifests for own teams and permitted environments, read own team's requests, list own team's approvals with `teamId`. |

Unauthenticated paths: `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, `/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**`, `/webjars/**`, plus anything in `governance.security.extra-permit-paths`. The internal bundle path uses a separate static token (see below).

## Error envelope

Every error response carries the same body:

```json
{
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "...",
  "timestamp": "2026-11-19T08:00:00Z",
  "errors": ["field: message"],
  "details": { "requestId": "..." }
}
```

`errors` is populated for 400s and otherwise empty. `details` is code-specific and otherwise empty.

| HTTP | code | When | details |
|---|---|---|---|
| 400 | `MANIFEST_INVALID` | Manifest failed structural validation in the submission service. | `errors` lists the problems. |
| 400 | `BAD_REQUEST` | Bean validation failure, unreadable body, bad query parameter, or an `IllegalArgumentException` from a service. | `errors` lists field messages. |
| 403 | `FORBIDDEN` | Caller lacks the role, team membership or environment access. | |
| 404 | `NOT_FOUND` | Unknown request, approval, exception, version, or no plan yet. | |
| 409 | `IDEMPOTENCY_CONFLICT` | Same `Idempotency-Key` reused with a different manifest. | `requestId` of the original. |
| 409 | `APPROVAL_RULE` | Approver role missing, separation of duties, approval not pending, or similar rule. | |
| 409 | `APPROVAL_CONFLICT` | Two verdicts raced on the same workflow step. Retry. `Retry-After: 1`. | |
| 409 | `STATE_CONFLICT` | Illegal state transition, or the workflow task behind the approval is gone. | |
| 409 | `TEAM_CONFIGURATION_EXTERNAL` | Team row is owned by an external registry and the write did not take ownership back. | |
| 409 | `POLICY_SOURCE_IS_GIT` | Bundle upload, activate or rollback called in release mode. | |
| 503 | `POLICY_EVALUATION_UNAVAILABLE` | Submission recorded but OPA could not evaluate it. Retry with the same key. `Retry-After: 10`. | `requestId`, `decisionId` |
| 503 | `POLICY_ENGINE_UNAVAILABLE` | OPA unreachable on any other call (catalogue, approval revalidation). `Retry-After: 10`. | |
| 500 | `INTERNAL_ERROR` | Anything unhandled. | |

## Manifests

### `POST /api/v1/manifests` — submit a complete desired-state manifest (stage 1)

Authorization: member of `teamId` with access to `environment`.

- Header `Idempotency-Key` (optional). Same key and same manifest replays the stored outcome with 200. Same key and different manifest is 409.
- Body: [ManifestRequestBody](#manifestrequestbody). `teamId`, `repositoryId`, `environment` and `resources` are required.
- 202: [AcceptedResponse](#acceptedresponse). `status` is `ACCEPTED` (RECEIVED, SUBMITTED, PLAN_PROPOSED, PLAN_AUTHORIZED), `PENDING_APPROVAL` (either stage), or the raw request status otherwise. Header `Location: /api/v1/requests/{requestId}`.
- 200: same body when replayed from the idempotency key.
- 422: [RejectedResponse](#rejectedresponse) with `status: "REJECTED"`, the violations and approval requirements. Also carries `Location`.
- 400 / 403 / 409 / 503: see the error envelope. 503 means the request exists with status EVALUATION_ERROR and should be retried with the same key.

## Requests

Readable by the requester, members of the request's team, approvers, auditors and admins.

| Method and path | Returns |
|---|---|
| `GET /api/v1/requests/{requestId}` | [RequestResponse](#requestresponse); 404 when unknown |
| `GET /api/v1/requests/{requestId}/decisions` | array of [GovernanceDecision](#governancedecision) |
| `GET /api/v1/requests/{requestId}/plan` | [ProposedPlan](#proposedplan); 404 when no plan has been proposed |

## Approvals

### `GET /api/v1/approvals` — list approvals

Approvers and auditors see all. Plain team members must pass `teamId` of one of their teams.

- Query: `status` ([ApprovalStatus](#enumerations), optional), `teamId` (optional), `limit` (default 100).
- 200: array of [ApprovalRequest](#approvalrequest).

### `GET /api/v1/approvals/{approvalId}` — approval with recorded verdicts

- 200: [ApprovalDetail](#approvaldetail).

### `POST /api/v1/approvals/{approvalId}/decisions` — record a verdict

Authorization: approver role.

- Body: `{ "verdict": "APPROVE" | "REJECT", "comment": "optional, max 2000 chars" }`
- 200: [ApprovalDetail](#approvaldetail). Idempotent per approver. When the quorum is reached the request is revalidated with OPA and released inside the same transaction, so `requestStatus` in the response already reflects the release.
- 409: `APPROVAL_RULE`, `APPROVAL_CONFLICT` or `STATE_CONFLICT`.
- 503: OPA failed during revalidation. The verdict is rolled back.

## Administration: policy configuration, bundles and releases

Reads need auditor, writes need admin. In release mode (`openshift` profile) every bundle write answers 409 `POLICY_SOURCE_IS_GIT`.

### Policy configuration (runtime parameters passed to OPA as data)

| Method and path | Role | Body | Returns |
|---|---|---|---|
| `GET /api/v1/admin/policy-configurations` | auditor | | array of [PolicyConfiguration](#policyconfiguration) |
| `GET /api/v1/admin/policy-configurations/{id}` | auditor | | PolicyConfiguration |
| `POST /api/v1/admin/policy-configurations` | admin | `{ "content": { ... }, "comment": "optional" }` | PolicyConfiguration with status DRAFT |
| `POST /api/v1/admin/policy-configurations/{id}/activate` | admin | | PolicyConfiguration; retires the current one |
| `POST /api/v1/admin/policy-configurations/rollback` | admin | | the re-activated previous PolicyConfiguration |

### Policy bundles (one built Rego bundle per root `governance/<name>`)

| Method and path | Role | Body or query | Returns |
|---|---|---|---|
| `GET /api/v1/admin/policy-bundles` | auditor | | array of [PolicyBundleVersion](#policybundleversion) |
| `GET /api/v1/admin/policy-bundles/{id}` | auditor | | PolicyBundleVersion |
| `GET /api/v1/admin/policy-bundles/diagnostics` | auditor | | [RevisionDiagnostics](#revisiondiagnostics) (same data as `/policy-releases/current`) |
| `POST /api/v1/admin/policy-bundles` | admin | `{ "bundleBase64": "<tar.gz, base64>", "testReportJson": "<opa test JSON>", "comment": "optional" }` | PolicyBundleVersion with status UPLOADED |
| `POST /api/v1/admin/policy-bundles/{id}/activate` | admin | | PolicyBundleVersion; a version OPA does not adopt is reverted by the verification job |
| `POST /api/v1/admin/policy-bundles/rollback?bundle={name}` | admin | `bundle` required | the restored PolicyBundleVersion |

The upload body is limited by `GOVERNANCE_MAX_BODY` (8MB by default). The bundle root must match the bundle name. No endpoint accepts raw Rego.

### Policy releases (one active version per bundle, switched atomically)

| Method and path | Role | Body | Returns |
|---|---|---|---|
| `GET /api/v1/admin/policy-releases/current` | auditor | | [RevisionDiagnostics](#revisiondiagnostics) including `mode` and `source` |
| `POST /api/v1/admin/policy-releases` | admin | `{ "versionIds": ["..."] }`, one uploaded version id per bundle, all six required | [PolicyRelease](#policyrelease) |

### Policy catalogue

`GET /api/v1/admin/policies` (auditor) returns an array relayed from OPA's `data.governance.core.policy_catalog`. Each entry: `policyId`, `bundle`, `kind` (VIOLATION or APPROVAL), `section`, `severity`, `bypassable`, `stages`, `resourceType`, `description`. Empty when no bundle is loaded; 503 `POLICY_ENGINE_UNAVAILABLE` when OPA is unreachable.

### Approval configuration

| Method and path | Role | Body | Returns |
|---|---|---|---|
| `GET /api/v1/admin/approval-configurations` | auditor | | array of [ApprovalConfiguration](#approvalconfiguration) |
| `POST /api/v1/admin/approval-configurations` | admin | `{ "approverRoles": ["governance-approver"], "requiredApprovals": 1..10, "separationOfDuties": true, "ttlHours": 1..720 }` | ApprovalConfiguration; audited as APPROVAL_CONFIGURATION_CREATED |
| `POST /api/v1/admin/approval-configurations/{id}/activate` | admin | | the now active ApprovalConfiguration |

## Administration: scoped exceptions

Time-boxed exemptions from one bypassable policy. Clients can never supply exceptions in a manifest.

| Method and path | Role | Body or query | Returns |
|---|---|---|---|
| `GET /api/v1/admin/exceptions` | auditor | `teamId` (optional), `includeInactive` (default false) | array of [PolicyException](#policyexception) |
| `GET /api/v1/admin/exceptions/{id}` | auditor | | PolicyException |
| `POST /api/v1/admin/exceptions` | admin | see below | PolicyException |
| `DELETE /api/v1/admin/exceptions/{id}?reason=` | admin | `reason` optional | the revoked PolicyException (idempotent) |

Create body:

```json
{
  "policyId": "required",
  "teamId": "required",
  "principalId": null,
  "environment": "*",
  "resourcePattern": "orders.*",
  "actionTypes": [],
  "expiresAt": "2026-12-31T00:00:00Z",
  "reason": "required"
}
```

`principalId` null means any principal of the team. `environment` is `*` or one environment. `resourcePattern` is a glob on the resource name. Empty `actionTypes` means any action type.

## Administration: team configuration and usage

Governance is the system of record. A row with `source: external` is read-only until an admin takes ownership back by writing `source: governance`.

| Method and path | Role | Returns |
|---|---|---|
| `GET /api/v1/admin/teams` | auditor | array of [TeamConfiguration](#teamconfiguration) |
| `GET /api/v1/admin/teams/{teamId}` | auditor | TeamConfiguration. Never 404: a team without a row gets `source: default` and `version: 0` |
| `PUT /api/v1/admin/teams/{teamId}` | admin | TeamConfiguration with the version bumped; writes history and audit; 409 `TEAM_CONFIGURATION_EXTERNAL` |
| `DELETE /api/v1/admin/teams/{teamId}` | admin | the default TeamConfiguration the team now falls back to |
| `GET /api/v1/admin/teams/{teamId}/history` | auditor | array of [TeamConfigurationChange](#teamconfigurationchange), newest first |
| `GET /api/v1/admin/teams/{teamId}/usage` | auditor | [TeamUsage](#teamusage) |

PUT body:

```json
{
  "authorizedPrefixes": ["orders.", "payments."],
  "quotas": { "topics": 50, "topicPartitions": 600, "schemas": 100, "identityPools": 5,
              "serviceAccounts": 10, "connectors": 5, "rbacBindings": 100, "flinkStatements": 10 },
  "allowedRoles": ["DeveloperRead", "DeveloperWrite"],
  "environments": { "prod": {} },
  "source": "governance",
  "sourceVersion": "optional",
  "comment": "optional"
}
```

`source` is `governance` or `external`. `environments` is free-form per environment.

## Administration: audit, dead letters and shadow findings

| Method and path | Role | Query | Returns |
|---|---|---|---|
| `GET /api/v1/admin/audit` | auditor | `targetType`, `targetId`, `requestId`, `actor`, `since` (ISO instant), all optional; `limit` default 200, clamped to 1..1000 | array of [AuditEntry](#auditentry) |
| `GET /api/v1/admin/dead-letters` | auditor | `limit` default 100, clamped to 1..1000 | array of [DeadLetter](#deadletter) |
| `POST /api/v1/admin/dead-letters/{id}/resolve` | admin | | `{ "id": 42, "resolved": true }`; `resolved` is false when the row was already resolved or unknown |
| `GET /api/v1/admin/shadow-findings` | auditor | `policyId`, `teamId`, `environment`, `since`, all optional; `limit` default 200, clamped to 1..1000 | array of [ShadowFinding](#shadowfinding), newest first |

## Internal: OPA bundle service

Hidden from the OpenAPI document. Guarded by a static bearer token checked by `BundleTokenFilter`, not by JWT. Meant for the OPA sidecar on the internal network only.

### `GET /internal/opa/bundles/{name}`

- Header `If-None-Match` with the previous ETag.
- 200: `application/octet-stream` tar.gz with headers `ETag: "<sha256>"` and `X-Bundle-Revision`.
- 304: ETag matches.
- 404: unknown bundle name, no active version, or the service runs in release mode.

## Schemas

Field types follow the Java records. Instants serialise as ISO 8601 strings. A trailing `?` marks a nullable field.

### ManifestRequestBody

```
{
  "teamId": string,            // required
  "repositoryId": string,      // required
  "environment": string,       // required
  "resources": {               // required
    "topics":        [ { "name": string, "partitions": int?, "retentionMs": long?, "config": { k: v }? } ],
    "rbac":          [ { "principal": string, "role": string, "resource": string? } ],
    "identityPools": [ { "name": string, "principals": [string]?, "rbac": [ RbacBody ]? } ]
  }
}
```

Omitted lists are treated as empty. A missing `resource` on an RBAC entry becomes `""`.

### AcceptedResponse

```
{ "requestId": string, "status": "ACCEPTED" | "PENDING_APPROVAL" | RequestStatus, "statusUrl": "/api/v1/requests/{id}", "decisionId": string? }
```

### RejectedResponse

```
{ "requestId": string, "status": "REJECTED", "decisionId": string?, "policyRevision": string?,
  "violations": [ PolicyViolation ], "approvalRequirements": [ ApprovalRequirement ] }
```

### RequestResponse

```
{
  "requestId": string, "status": RequestStatus, "teamId": string, "repositoryId": string, "environment": string,
  "principalId": string, "manifestDigest": string, "planId": string?, "planDigest": string?, "stateVersion": long?,
  "failureReason": string?, "createdAt": instant, "updatedAt": instant, "completedAt": instant?,
  "manifest": Manifest,          // same shape as ManifestRequestBody
  "history": [ { "fromStatus": string?, "toStatus": string, "reason": string?, "eventId": string?, "occurredAt": instant } ]
}
```

### GovernanceDecision

```
{
  "id": string, "requestId": string, "planId": string?,
  "stage": "REQUEST" | "ACTION_PLAN", "type": "ALLOW" | "DENY" | "REQUIRE_APPROVAL" | "ERROR",
  "policyRevision": string,              // release id, "" when none was active
  "policyRevisions": { "<bundle>": rev }, // revision per bundle as reported by OPA
  "configurationVersion": long, "digest": string,
  "matchedPolicies": [string],
  "violations": [ PolicyViolation ], "approvalRequirements": [ ApprovalRequirement ],
  "appliedExceptions": [exceptionId],
  "errorMessage": string?, "opaLatencyMs": long?, "evaluatedAt": instant
}
```

### PolicyViolation and ApprovalRequirement

```
PolicyViolation {
  "policyId": string, "severity": "HARD_DENY" | "DENY", "message": string,
  "resourceType": string, "resourceName": string,
  "bypassable": bool, "exempted": bool, "exceptionId": string?,
  "enforcement": "enforce" | "shadow"
}
ApprovalRequirement {
  "policyId": string, "message": string, "resourceType": string, "resourceName": string,
  "enforcement": "enforce" | "shadow"
}
```

### ProposedPlan

```
{
  "planId": string, "requestId": string, "planDigest": string, "manifestDigest": string, "stateVersion": long,
  "actions": [ { "actionId": string, "resourceType": string, "resourceName": string, "actionType": string, "payload": { k: v } } ],
  "eventId": string, "receivedAt": instant
}
```

### ApprovalRequest

```
{
  "id": string, "requestId": string, "stage": "REQUEST" | "ACTION_PLAN", "decisionId": string,
  "planId": string?, "planDigest": string?, "digest": string,
  "status": ApprovalStatus, "requesterId": string,
  "requiredApprovals": int, "approverRoles": [string], "separationOfDuties": bool,
  "requirements": [ ApprovalRequirement ],
  "expiresAt": instant, "version": long, "createdAt": instant, "resolvedAt": instant?, "resolutionReason": string?,
  "engine": "LEGACY" | "FLOWABLE", "processInstanceId": string?
}
```

### ApprovalDetail

```
{
  "approval": ApprovalRequest,
  "decisions": [ { "id": string, "approvalId": string, "approverId": string, "verdict": "APPROVE" | "REJECT", "comment": string?, "decidedAt": instant } ],
  "requestStatus": RequestStatus, "teamId": string, "repositoryId": string, "environment": string
}
```

### PolicyConfiguration

```
{ "id": string, "version": long, "content": { ... }, "status": VersionStatus, "comment": string?,
  "createdBy": string, "createdAt": instant, "activatedAt": instant?, "retiredAt": instant? }
```

### PolicyBundleVersion

```
{ "id": string, "bundleName": string, "revision": string, "sha256": string,
  "manifest": { ... bundle .manifest ... }, "testReport": { ... opa test JSON ... },
  "status": VersionStatus, "comment": string?, "uploadedBy": string, "uploadedAt": instant,
  "activatedAt": instant?, "retiredAt": instant?, "failureReason": string? }
```

### PolicyRelease

```
{ "id": string, "revisions": { "changes": rev, "core": rev, "manifests": rev, "naming": rev, "quotas": rev, "resources": rev } }
```

### RevisionDiagnostics

```
{ "releaseId": string?, "match": bool, "ready": bool,
  "bundles": { "<name>": { "expected": rev?, "loaded": rev?, "match": bool } },
  "mode": "local" | "release", "source": string }
```

### ApprovalConfiguration

```
{ "id": string, "version": long, "approverRoles": [string], "requiredApprovals": int, "separationOfDuties": bool,
  "ttlHours": int, "status": VersionStatus, "createdBy": string, "createdAt": instant, "activatedAt": instant? }
```

### PolicyException

```
{ "id": string, "policyId": string, "principalId": string?, "teamId": string, "environment": string,
  "resourcePattern": string?, "actionTypes": [string], "reason": string, "expiresAt": instant,
  "createdBy": string, "createdAt": instant, "revokedBy": string?, "revokedAt": instant?, "revokeReason": string? }
```

### TeamConfiguration

```
{ "teamId": string, "version": long, "authorizedPrefixes": [string], "quotas": { "topics": long, ... },
  "allowedRoles": [string], "environments": { ... }, "source": "governance" | "external" | "default",
  "sourceVersion": string?, "syncedAt": instant?, "comment": string?, "updatedBy": string?,
  "createdAt": instant?, "updatedAt": instant? }
```

### TeamConfigurationChange

```
{ "id": long, "teamId": string, "version": long, "content": { ... }, "action": string,
  "changedBy": string, "changedAt": instant, "comment": string? }
```

### TeamUsage

```
{
  "teamId": string,
  "currentByEnvironment":  { "<env>": { "topics": long, ... } },
  "reservedByEnvironment": { "<env>": { "topics": long, ... } },
  "repositories": [ { "repositoryId": string, "environment": string, "stateVersion": long, "counts": { ... },
                      "topics": [string], "identityPools": [string], "rbacBindings": [string], "snapshotAt": instant } ],
  "reservations": [ { "id": string, "environment": string, "requestId": string, "planId": string?,
                      "deltas": { ... }, "createdAt": instant, "expiresAt": instant } ]
}
```

### AuditEntry

```
{ "id": long, "occurredAt": instant, "actorId": string, "action": string, "targetType": string,
  "targetId": string, "requestId": string?, "details": { ... } }
```

### DeadLetter

```
{ "id": long, "topic": string, "partition": int, "offset": long, "key": string?, "eventId": string?,
  "requestId": string?, "reason": string, "error": string?, "recordedAt": instant, "resolvedAt": instant? }
```

### ShadowFinding

```
{ "id": long, "decisionId": string, "requestId": string, "planId": string?, "stage": string, "kind": string,
  "policyId": string, "severity": string?, "message": string, "resourceType": string, "resourceName": string,
  "recordedAt": instant, "teamId": string, "repositoryId": string, "environment": string, "principalId": string }
```

## Enumerations

| Type | Values |
|---|---|
| RequestStatus | `RECEIVED`, `STAGE1_DENIED`, `PENDING_APPROVAL_STAGE1`, `SUBMITTED`, `PLAN_PROPOSED`, `PENDING_APPROVAL_STAGE2`, `PLAN_AUTHORIZED`, `PLAN_REJECTED`, `EVALUATION_ERROR`, `APPROVAL_EXPIRED`, `STALE_PLAN`, `REJECTED_BY_ORCHESTRATOR`, `COMPLETED`, `FAILED`. Terminal: STAGE1_DENIED, PLAN_REJECTED, APPROVAL_EXPIRED, STALE_PLAN, REJECTED_BY_ORCHESTRATOR, COMPLETED, FAILED. |
| ApprovalStatus | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED` |
| ApprovalVerdict | `APPROVE`, `REJECT` |
| ApprovalEngine | `LEGACY`, `FLOWABLE` |
| GovernanceStage | `REQUEST` (stage 1, manifest), `ACTION_PLAN` (stage 2, plan) |
| DecisionType | `ALLOW`, `DENY`, `REQUIRE_APPROVAL`, `ERROR` |
| VersionStatus | `DRAFT`, `UPLOADED`, `ACTIVE`, `RETIRED`, `FAILED` |
| Violation severity | `HARD_DENY` (never exemptable or approvable), `DENY` |
| Enforcement | `enforce`, `shadow` |
| Quota keys | `topics`, `topicPartitions`, `schemas`, `identityPools`, `serviceAccounts`, `connectors`, `rbacBindings`, `flinkStatements` |

## Caveats observed while deriving this contract

- **Admin body constraints are annotated but not enforced.** The bodies for exceptions, policy bundles, policy configurations, policy releases and approval configurations carry `@NotBlank`, `@NotNull`, `@NotEmpty`, `@Min` and `@Max`, but their handlers take `@RequestBody` without `@Valid`. Only the manifest and approval decision bodies are validated by Spring. A missing field on an admin body reaches the service, which may answer 400, 409 or 500 depending on what it hits.
- **No pagination.** List endpoints take a `limit` only where noted and return plain arrays. There is no cursor or offset.
- **Two routes for the same diagnostics.** `/admin/policy-bundles/diagnostics` and `/admin/policy-releases/current` return the same RevisionDiagnostics object.

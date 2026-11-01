import { apiFetch } from './client';
import type {
  ApprovalConfiguration,
  AuditEntry,
  AuditQuery,
  CatalogPolicy,
  CreateApprovalConfigurationBody,
  CreateExceptionBody,
  CreatePolicyConfigurationBody,
  DeadLetter,
  PolicyBundleVersion,
  PolicyConfiguration,
  PolicyException,
  PolicyReleaseActivation,
  RevisionDiagnostics,
  ShadowFinding,
  ShadowFindingQuery,
  TeamConfiguration,
  TeamConfigurationChange,
  TeamUsage,
  UploadBundleBody,
  UpsertTeamBody,
} from './types';

const ADMIN = '/api/v1/admin';

export const exceptionsApi = {
  list: (q: { teamId?: string; includeInactive?: boolean } = {}) =>
    apiFetch<PolicyException[]>(`${ADMIN}/exceptions`, { query: q }),
  get: (id: string) => apiFetch<PolicyException>(`${ADMIN}/exceptions/${encodeURIComponent(id)}`),
  create: (body: CreateExceptionBody) =>
    apiFetch<PolicyException>(`${ADMIN}/exceptions`, { method: 'POST', body }),
  revoke: (id: string, reason?: string) =>
    apiFetch<PolicyException>(`${ADMIN}/exceptions/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      query: { reason },
    }),
};

export const policyConfigApi = {
  list: () => apiFetch<PolicyConfiguration[]>(`${ADMIN}/policy-configurations`),
  get: (id: string) =>
    apiFetch<PolicyConfiguration>(`${ADMIN}/policy-configurations/${encodeURIComponent(id)}`),
  create: (body: CreatePolicyConfigurationBody) =>
    apiFetch<PolicyConfiguration>(`${ADMIN}/policy-configurations`, { method: 'POST', body }),
  activate: (id: string) =>
    apiFetch<PolicyConfiguration>(`${ADMIN}/policy-configurations/${encodeURIComponent(id)}/activate`, {
      method: 'POST',
    }),
  rollback: () =>
    apiFetch<PolicyConfiguration>(`${ADMIN}/policy-configurations/rollback`, { method: 'POST' }),
};

export const bundlesApi = {
  list: () => apiFetch<PolicyBundleVersion[]>(`${ADMIN}/policy-bundles`),
  get: (id: string) => apiFetch<PolicyBundleVersion>(`${ADMIN}/policy-bundles/${encodeURIComponent(id)}`),
  diagnostics: () => apiFetch<RevisionDiagnostics>(`${ADMIN}/policy-bundles/diagnostics`),
  upload: (body: UploadBundleBody) =>
    apiFetch<PolicyBundleVersion>(`${ADMIN}/policy-bundles`, { method: 'POST', body }),
  activate: (id: string) =>
    apiFetch<PolicyBundleVersion>(`${ADMIN}/policy-bundles/${encodeURIComponent(id)}/activate`, {
      method: 'POST',
    }),
  rollback: (bundle: string) =>
    apiFetch<PolicyBundleVersion>(`${ADMIN}/policy-bundles/rollback`, { method: 'POST', query: { bundle } }),
};

export const releasesApi = {
  current: () => apiFetch<RevisionDiagnostics>(`${ADMIN}/policy-releases/current`),
  activate: (versionIds: string[]) =>
    apiFetch<PolicyReleaseActivation>(`${ADMIN}/policy-releases`, { method: 'POST', body: { versionIds } }),
};

export const approvalConfigApi = {
  list: () => apiFetch<ApprovalConfiguration[]>(`${ADMIN}/approval-configurations`),
  create: (body: CreateApprovalConfigurationBody) =>
    apiFetch<ApprovalConfiguration>(`${ADMIN}/approval-configurations`, { method: 'POST', body }),
  activate: (id: string) =>
    apiFetch<ApprovalConfiguration>(`${ADMIN}/approval-configurations/${encodeURIComponent(id)}/activate`, {
      method: 'POST',
    }),
};

export const auditApi = {
  query: (q: AuditQuery = {}) => apiFetch<AuditEntry[]>(`${ADMIN}/audit`, { query: { ...q } }),
  deadLetters: (limit = 100) => apiFetch<DeadLetter[]>(`${ADMIN}/dead-letters`, { query: { limit } }),
  resolveDeadLetter: (id: number) =>
    apiFetch<{ id: number; resolved: boolean }>(`${ADMIN}/dead-letters/${id}/resolve`, { method: 'POST' }),
};

export const catalogApi = {
  list: () => apiFetch<CatalogPolicy[]>(`${ADMIN}/policies`),
};

export const teamsApi = {
  list: () => apiFetch<TeamConfiguration[]>(`${ADMIN}/teams`),
  get: (teamId: string) => apiFetch<TeamConfiguration>(`${ADMIN}/teams/${encodeURIComponent(teamId)}`),
  upsert: (teamId: string, body: UpsertTeamBody) =>
    apiFetch<TeamConfiguration>(`${ADMIN}/teams/${encodeURIComponent(teamId)}`, { method: 'PUT', body }),
  remove: (teamId: string) =>
    apiFetch<TeamConfiguration>(`${ADMIN}/teams/${encodeURIComponent(teamId)}`, { method: 'DELETE' }),
  history: (teamId: string) =>
    apiFetch<TeamConfigurationChange[]>(`${ADMIN}/teams/${encodeURIComponent(teamId)}/history`),
  usage: (teamId: string) => apiFetch<TeamUsage>(`${ADMIN}/teams/${encodeURIComponent(teamId)}/usage`),
};

export const shadowApi = {
  list: (q: ShadowFindingQuery = {}) => apiFetch<ShadowFinding[]>(`${ADMIN}/shadow-findings`, { query: { ...q } }),
};

import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import {
  approvalConfigApi,
  auditApi,
  bundlesApi,
  catalogApi,
  exceptionsApi,
  policyConfigApi,
  releasesApi,
} from './endpoints';
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
  UploadBundleBody,
} from './types';

export const queryKeys = {
  exceptions: (q: { teamId?: string; includeInactive?: boolean } = {}) => ['exceptions', q] as const,
  exception: (id: string) => ['exceptions', 'one', id] as const,
  policyConfigs: ['policy-configurations'] as const,
  policyConfig: (id: string) => ['policy-configurations', id] as const,
  bundles: ['policy-bundles'] as const,
  bundle: (id: string) => ['policy-bundles', id] as const,
  diagnostics: ['policy-bundles', 'diagnostics'] as const,
  release: ['policy-releases', 'current'] as const,
  approvalConfigs: ['approval-configurations'] as const,
  audit: (q: AuditQuery) => ['audit', q] as const,
  deadLetters: ['dead-letters'] as const,
  catalog: ['policy-catalog'] as const,
};

type Opts<T> = Omit<UseQueryOptions<T, Error>, 'queryKey' | 'queryFn'>;

// ---- exceptions
export function useExceptions(
  q: { teamId?: string; includeInactive?: boolean } = {},
  opts?: Opts<PolicyException[]>,
) {
  return useQuery({ queryKey: queryKeys.exceptions(q), queryFn: () => exceptionsApi.list(q), ...opts });
}
export function useException(id: string, opts?: Opts<PolicyException>) {
  return useQuery({ queryKey: queryKeys.exception(id), queryFn: () => exceptionsApi.get(id), ...opts });
}
export function useCreateException() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateExceptionBody) => exceptionsApi.create(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['exceptions'] }),
  });
}
export function useRevokeException() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) => exceptionsApi.revoke(id, reason),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['exceptions'] }),
  });
}

// ---- policy configuration
export function usePolicyConfigs(opts?: Opts<PolicyConfiguration[]>) {
  return useQuery({ queryKey: queryKeys.policyConfigs, queryFn: policyConfigApi.list, ...opts });
}
export function usePolicyConfig(id: string, opts?: Opts<PolicyConfiguration>) {
  return useQuery({ queryKey: queryKeys.policyConfig(id), queryFn: () => policyConfigApi.get(id), ...opts });
}
export function useCreatePolicyConfig() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreatePolicyConfigurationBody) => policyConfigApi.create(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.policyConfigs }),
  });
}
export function useActivatePolicyConfig() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => policyConfigApi.activate(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.policyConfigs }),
  });
}
export function useRollbackPolicyConfig() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => policyConfigApi.rollback(),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.policyConfigs }),
  });
}

// ---- bundles
export function useBundles(opts?: Opts<PolicyBundleVersion[]>) {
  return useQuery({ queryKey: queryKeys.bundles, queryFn: bundlesApi.list, ...opts });
}
export function useDiagnostics(opts?: Opts<RevisionDiagnostics>) {
  return useQuery({ queryKey: queryKeys.diagnostics, queryFn: bundlesApi.diagnostics, ...opts });
}
export function useUploadBundle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UploadBundleBody) => bundlesApi.upload(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.bundles }),
  });
}
export function useActivateBundle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => bundlesApi.activate(id),
    onSuccess: () => invalidatePolicySet(qc),
  });
}
export function useRollbackBundle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (bundle: string) => bundlesApi.rollback(bundle),
    onSuccess: () => invalidatePolicySet(qc),
  });
}

// ---- policy release (the set of ACTIVE bundle versions)
export function useReleaseStatus(opts?: Opts<RevisionDiagnostics>) {
  return useQuery({ queryKey: queryKeys.release, queryFn: releasesApi.current, ...opts });
}
export function useActivateRelease() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (versionIds: string[]): Promise<PolicyReleaseActivation> => releasesApi.activate(versionIds),
    onSuccess: () => invalidatePolicySet(qc),
  });
}
function invalidatePolicySet(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: ['policy-bundles'] });
  void qc.invalidateQueries({ queryKey: ['policy-releases'] });
  void qc.invalidateQueries({ queryKey: queryKeys.catalog });
}

// ---- approval configuration
export function useApprovalConfigs(opts?: Opts<ApprovalConfiguration[]>) {
  return useQuery({ queryKey: queryKeys.approvalConfigs, queryFn: approvalConfigApi.list, ...opts });
}
export function useCreateApprovalConfig() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateApprovalConfigurationBody) => approvalConfigApi.create(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.approvalConfigs }),
  });
}
export function useActivateApprovalConfig() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => approvalConfigApi.activate(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.approvalConfigs }),
  });
}

// ---- audit / operations / catalogue
export function useAudit(q: AuditQuery, opts?: Opts<AuditEntry[]>) {
  return useQuery({ queryKey: queryKeys.audit(q), queryFn: () => auditApi.query(q), ...opts });
}
export function useDeadLetters(opts?: Opts<DeadLetter[]>) {
  return useQuery({ queryKey: queryKeys.deadLetters, queryFn: () => auditApi.deadLetters(100), ...opts });
}
export function useResolveDeadLetter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => auditApi.resolveDeadLetter(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.deadLetters }),
  });
}
export function useCatalog(opts?: Opts<CatalogPolicy[]>) {
  return useQuery({ queryKey: queryKeys.catalog, queryFn: catalogApi.list, staleTime: 5 * 60_000, ...opts });
}

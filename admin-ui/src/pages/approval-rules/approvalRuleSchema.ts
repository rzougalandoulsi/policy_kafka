import { z } from 'zod';
import type { ApprovalConfiguration, CreateApprovalConfigurationBody } from '@/api/types';

/** Mirrors ApprovalConfigurationAdminController.CreateBody bean validation. */
export const approvalRuleSchema = z.object({
  approverRoles: z.array(z.string().trim().min(1)).min(1, 'At least one approver role is required'),
  requiredApprovals: z.coerce
    .number({ invalid_type_error: 'Must be a number' })
    .int('Must be a whole number')
    .min(1, 'At least 1 approval')
    .max(10, 'At most 10 approvals'),
  separationOfDuties: z.boolean(),
  ttlHours: z.coerce
    .number({ invalid_type_error: 'Must be a number' })
    .int('Must be a whole number')
    .min(1, 'At least 1 hour')
    .max(720, 'At most 720 hours (30 days)'),
  activateImmediately: z.boolean(),
});

export type ApprovalRuleForm = z.infer<typeof approvalRuleSchema>;

export const TTL_PRESETS = [24, 72, 168] as const;

export function defaultsFrom(active: ApprovalConfiguration | undefined): ApprovalRuleForm {
  return {
    approverRoles: active?.approverRoles ?? ['governance-approver'],
    requiredApprovals: active?.requiredApprovals ?? 1,
    separationOfDuties: active?.separationOfDuties ?? true,
    ttlHours: active?.ttlHours ?? 72,
    activateImmediately: false,
  };
}

export function toBody(f: ApprovalRuleForm): CreateApprovalConfigurationBody {
  return {
    approverRoles: f.approverRoles,
    requiredApprovals: f.requiredApprovals,
    separationOfDuties: f.separationOfDuties,
    ttlHours: f.ttlHours,
  };
}

/** Plain-words summary of a quorum rule. */
export function describeRule(r: {
  approverRoles: string[];
  requiredApprovals: number;
  separationOfDuties: boolean;
  ttlHours: number;
}): string {
  const n = r.requiredApprovals;
  const roles = r.approverRoles.length ? r.approverRoles.join(', ') : '(no roles)';
  const sod = r.separationOfDuties ? ' The requester can never approve their own request.' : '';
  return `${n} approval${n === 1 ? '' : 's'} from any of the roles ${roles} within ${r.ttlHours} hour${r.ttlHours === 1 ? '' : 's'}.${sod}`;
}

import { z } from 'zod';
import type { CreateExceptionBody } from '@/api/types';
import { fromDateTimeLocal } from '@/lib/time';

/** Mirrors ExceptionAdminService.create: policyId pattern, required team/reason, expiry window. */
export const POLICY_ID_PATTERN = /^[A-Z][A-Z0-9_]{2,63}$/;
export const MAX_TTL_DAYS = 365;

export function buildExceptionSchema(now: () => Date = () => new Date()) {
  return z.object({
    policyId: z
      .string()
      .trim()
      .min(1, 'Policy is required')
      .regex(POLICY_ID_PATTERN, 'Policy id must match ^[A-Z][A-Z0-9_]{2,63}$'),
    teamId: z.string().trim().min(1, 'Team is required'),
    environment: z.string().trim(),
    principalId: z.string().trim(),
    resourcePattern: z.string().trim(),
    actionTypes: z.array(z.string().trim().min(1)),
    expiresAt: z
      .string()
      .min(1, 'Expiry is required')
      .superRefine((v, ctx) => {
        const d = fromDateTimeLocal(v);
        if (!d) return ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Expiry is not a valid date' });
        const n = now();
        if (d.getTime() <= n.getTime())
          ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Expiry must be in the future' });
        if (d.getTime() > n.getTime() + MAX_TTL_DAYS * 24 * 3_600_000)
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `Expiry may be at most ${MAX_TTL_DAYS} days ahead`,
          });
      }),
    reason: z.string().trim().min(1, 'Reason is required').max(2000, 'Reason is too long'),
  });
}

export const exceptionSchema = buildExceptionSchema();
export type ExceptionFormValues = z.infer<typeof exceptionSchema>;

export const emptyExceptionForm: ExceptionFormValues = {
  policyId: '',
  teamId: '',
  environment: '',
  principalId: '',
  resourcePattern: '',
  actionTypes: [],
  expiresAt: '',
  reason: '',
};

/** Form -> API body; blanks are omitted so the server applies its defaults ("*"). */
export function toCreateBody(v: ExceptionFormValues): CreateExceptionBody {
  const d = fromDateTimeLocal(v.expiresAt);
  return {
    policyId: v.policyId,
    teamId: v.teamId,
    environment: v.environment || undefined,
    principalId: v.principalId || undefined,
    resourcePattern: v.resourcePattern || undefined,
    actionTypes: v.actionTypes,
    expiresAt: (d ?? new Date(v.expiresAt)).toISOString(),
    reason: v.reason,
  };
}

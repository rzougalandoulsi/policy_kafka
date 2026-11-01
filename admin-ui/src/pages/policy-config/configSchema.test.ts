import { validateContent } from './configSchema';
import { seedConfigContent } from '@/test/fixtures';

describe('validateContent (parity with PolicyConfigurationService.validate)', () => {
  it('accepts the seeded configuration', () => {
    expect(validateContent(seedConfigContent)).toEqual({ errors: [], warnings: [] });
  });
  it('reports missing object sections', () => {
    const { rbac: _rbac, ...noRbac } = seedConfigContent;
    const r = validateContent({ ...noRbac, approvals: 'nope' });
    expect(r.errors).toEqual(['Policy configuration is missing object sections: [rbac, approvals]']);
  });
  it('rejects non-positive or non-numeric limits', () => {
    const r = validateContent({
      ...seedConfigContent,
      limits: { ...seedConfigContent.limits, maxPartitionsPerTopic: 0, maxRetentionMs: '1' },
    });
    expect(r.errors).toEqual([
      'limits.maxPartitionsPerTopic must be a positive number',
      'limits.maxRetentionMs must be a positive number',
    ]);
  });
  it('warns on an invalid topic pattern without blocking', () => {
    const r = validateContent({
      ...seedConfigContent,
      naming: { ...seedConfigContent.naming, topicPattern: '[' },
    });
    expect(r.errors).toEqual([]);
    expect(r.warnings[0]).toMatch(/topicPattern/);
  });
  it('rejects non-objects', () => {
    expect(validateContent([]).errors[0]).toMatch(/JSON object/);
  });
});

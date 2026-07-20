import { EnumLabelPipe, enumLabel } from './enum-label.pipe';

describe('EnumLabelPipe', () => {
  it('translates technical statuses into Polish labels', () => {
    expect(enumLabel('SIGNED')).toBe('Podpisane');
    expect(enumLabel('AWAITING_REVIEW')).toBe('Do weryfikacji');
    expect(enumLabel('CREATED')).toBe('Utworzone');
  });

  it('creates a readable fallback for an unknown enum', () => {
    expect(new EnumLabelPipe().transform('CUSTOM_STATUS')).toBe('Custom status');
  });
});

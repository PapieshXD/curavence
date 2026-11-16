import { ClinicalResultParameter } from '../models/medical';
import {
  clinicalCategoryKey,
  clinicalCategoryLabel,
  clinicalReferenceLabel,
  clinicalResultStatus,
  clinicalValueLabel,
} from './clinical-results';

describe('clinical result helpers', () => {
  const parameter = (value: number): ClinicalResultParameter => ({
    id: 'hgb',
    name: 'Hemoglobina',
    value,
    unit: 'g/dl',
    referenceRange: { low: 12, high: 16 },
  });

  it('derives status from any numeric result and its reference range', () => {
    expect(clinicalResultStatus(parameter(11.2))).toBe('low');
    expect(clinicalResultStatus(parameter(13.4))).toBe('normal');
    expect(clinicalResultStatus(parameter(17))).toBe('high');
  });

  it('formats generic values and open-ended ranges', () => {
    expect(clinicalValueLabel(parameter(13.25))).toBe('13,25 g/dl');
    expect(clinicalReferenceLabel({ low: null, high: 5 }, 'mg/l')).toBe('do 5 mg/l');
  });

  it('accepts new backend categories without changing the UI registry', () => {
    const key = clinicalCategoryKey('Badania metaboliczne');
    expect(key).toBe('badania-metaboliczne');
    expect(clinicalCategoryLabel(key)).toBe('Badania metaboliczne');
  });
});

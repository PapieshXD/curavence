import {
  ClinicalResultInterpretation,
  ClinicalResultParameter,
  ClinicalResultReferenceRange,
} from '../models/medical';

export type ClinicalResultStatus = 'low' | 'normal' | 'high' | 'abnormal' | 'unknown';

const CATEGORY_LABELS: Record<string, string> = {
  blood: 'Krew',
  urine: 'Mocz',
  hormones: 'Hormony',
  microbiology: 'Mikrobiologia',
  histopathology: 'Histopatologia',
  allergy: 'Alergologia',
  genetics: 'Genetyka',
  other: 'Inne',
};

export function clinicalNumericValue(parameter: ClinicalResultParameter): number | null {
  if (typeof parameter.numericValue === 'number' && Number.isFinite(parameter.numericValue)) {
    return parameter.numericValue;
  }
  return typeof parameter.value === 'number' && Number.isFinite(parameter.value)
    ? parameter.value
    : null;
}

export function clinicalResultStatus(parameter: ClinicalResultParameter): ClinicalResultStatus {
  const explicit = interpretationStatus(parameter.interpretation);
  if (explicit !== 'unknown') return explicit;

  const value = clinicalNumericValue(parameter);
  const range = parameter.referenceRange;
  if (value == null || !range) return 'unknown';
  if (range.low != null && value < range.low) return 'low';
  if (range.high != null && value > range.high) return 'high';
  if (range.low != null || range.high != null) return 'normal';
  return 'unknown';
}

export function clinicalValueLabel(parameter: ClinicalResultParameter): string {
  const value = clinicalNumericValue(parameter);
  const raw = value == null ? parameter.value : formatClinicalNumber(value);
  const display = raw == null || raw === '' ? 'Brak wartości' : String(raw);
  return parameter.unit ? `${display} ${parameter.unit}` : display;
}

export function clinicalReferenceLabel(range: ClinicalResultReferenceRange | null | undefined, unit?: string | null): string {
  if (!range) return 'Laboratorium nie podało zakresu referencyjnego';
  if (range.label) return range.label;
  const suffix = unit ? ` ${unit}` : '';
  if (range.low != null && range.high != null) {
    return `${formatClinicalNumber(range.low)}–${formatClinicalNumber(range.high)}${suffix}`;
  }
  if (range.low != null) return `od ${formatClinicalNumber(range.low)}${suffix}`;
  if (range.high != null) return `do ${formatClinicalNumber(range.high)}${suffix}`;
  return 'Laboratorium nie podało zakresu referencyjnego';
}

export function clinicalCategoryKey(value: string | null | undefined): string {
  return value?.trim().toLocaleLowerCase('pl').replace(/[^a-z0-9ąćęłńóśźż]+/g, '-') || 'other';
}

export function clinicalCategoryLabel(key: string, provided?: string | null): string {
  if (provided?.trim()) return provided.trim();
  return CATEGORY_LABELS[key] ?? key.replace(/-/g, ' ').replace(/^./, (letter) => letter.toLocaleUpperCase('pl'));
}

export function clinicalStatusCopy(status: ClinicalResultStatus): string {
  return {
    low: 'Wynik poniżej zakresu referencyjnego.',
    normal: 'Wynik w zakresie referencyjnym.',
    high: 'Wynik powyżej zakresu referencyjnego.',
    abnormal: 'Wynik oznaczony przez laboratorium jako wymagający uwagi.',
    unknown: 'Brak automatycznej interpretacji wyniku.',
  }[status];
}

function interpretationStatus(value: ClinicalResultInterpretation | null | undefined): ClinicalResultStatus {
  return {
    LOW: 'low',
    NORMAL: 'normal',
    HIGH: 'high',
    ABNORMAL: 'abnormal',
    UNKNOWN: 'unknown',
  }[value ?? 'UNKNOWN'] as ClinicalResultStatus;
}

function formatClinicalNumber(value: number): string {
  return new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 }).format(value);
}

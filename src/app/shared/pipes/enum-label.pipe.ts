import { Pipe, PipeTransform } from '@angular/core';

const ENUM_LABELS: Record<string, string> = {
  ACTIVE: 'Aktywne',
  ADMINISTRATOR: 'Administrator',
  AWAITING_REVIEW: 'Do weryfikacji',
  CREATED: 'Utworzone',
  DIAGNOSTIC_REPORT: 'Raport diagnostyczny',
  DOCTOR: 'Lekarz',
  DRAFT: 'Szkic',
  EXPIRED: 'Wygasłe',
  FAILED: 'Błąd',
  FEMALE: 'Kobieta',
  FULL_STUDY: 'Pełny dostęp',
  LAB_RESULTS: 'Wyniki laboratoryjne',
  MALE: 'Mężczyzna',
  OTHER: 'Inna',
  PATIENT: 'Pacjent',
  PHYSIOTHERAPIST: 'Fizjoterapeuta',
  READY: 'Gotowe',
  REHABILITATION_SUMMARY: 'Rehabilitacja',
  REVOKED: 'Cofnięte',
  SELECTED_STUDIES: 'Wybrane badania',
  SIGNED: 'Podpisane',
  STORED: 'Zapisane',
  TRAINER: 'Trener',
  TRAINING_RESTRICTIONS: 'Ograniczenia treningowe',
  UPLOADING: 'Przesyłanie',
};

export function enumLabel(value: string | null | undefined): string {
  if (!value) return '';

  const knownLabel = ENUM_LABELS[value];
  if (knownLabel) return knownLabel;

  const normalized = value.replaceAll('_', ' ').toLocaleLowerCase('pl');
  return normalized.charAt(0).toLocaleUpperCase('pl') + normalized.slice(1);
}

@Pipe({ name: 'enumLabel', standalone: true })
export class EnumLabelPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return enumLabel(value);
  }
}

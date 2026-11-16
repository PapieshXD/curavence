import { StudyRecord } from '../../core/models/medical';

export type PatientStudyFilter = 'all' | 'laboratory' | 'imaging' | 'ecg' | 'documents';

export const PATIENT_STUDY_FILTERS: { id: PatientStudyFilter; label: string }[] = [
  { id: 'all', label: 'Wszystkie' },
  { id: 'laboratory', label: 'Krew i mocz' },
  { id: 'imaging', label: 'Obrazowe' },
  { id: 'ecg', label: 'EKG' },
  { id: 'documents', label: 'Dokumenty' },
];

export function patientStudyFilter(study: StudyRecord): PatientStudyFilter {
  if (study.typeCode === 'LABORATORY') return 'laboratory';
  if (['MRI', 'CT', 'XRAY', 'ULTRASOUND'].includes(study.typeCode) || study.imagingStudyId) return 'imaging';
  if (['EKG', 'ECG'].includes(study.typeCode)) return 'ecg';
  return 'documents';
}

export function patientStudyTitle(study: StudyRecord): string {
  const clean = study.title.replace(/^\d+[_\s-]*/, '').replace(/_/g, ' ').trim();
  if (study.typeCode === 'LABORATORY' && /kontrolne wyniki/i.test(clean)) return 'Morfologia krwi';
  if (study.typeCode === 'MRI' && /głow|brain|head/i.test(`${study.title} ${study.description ?? ''}`)) return 'Rezonans głowy';
  if (study.typeCode === 'CT' && /głow|brain|head/i.test(`${study.title} ${study.description ?? ''}`)) return 'Tomografia głowy';
  return (clean || study.typeName).replace(/^./, letter => letter.toLocaleUpperCase('pl'));
}

export function patientStudyStatus(study: StudyRecord): string {
  if (study.ingestionStatus === 'UPLOADING') return 'Plik się przesyła';
  if (study.ingestionStatus === 'FAILED') return study.primaryAssetId
    ? 'Nie udało się odczytać wartości — oryginał jest dostępny'
    : 'Nie udało się przesłać pliku';
  if (study.ingestionStatus !== 'READY') return 'Odczytujemy tekst';
  if (study.documentProcessingStatus === 'OCR_REQUIRED') return 'Nie udało się odczytać wartości — oryginał jest dostępny';
  if (study.clinicalStatus === 'SIGNED') return 'Opis lekarza jest gotowy';
  if (study.resultSet?.parameters?.length || study.resultSet?.sections?.length) return 'Sprawdź odczytane dane';
  if (patientStudyFilter(study) === 'imaging') return 'Opis czeka na zatwierdzenie';
  return study.primaryAssetId ? 'Oryginalny plik jest dostępny' : 'Opis czeka na zatwierdzenie';
}

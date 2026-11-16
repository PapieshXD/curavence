import { ReadingGuide } from '../../../../shared/components/reading-guide/reading-guide';
import { ExplainedText } from '../../../../shared/components/explained-text/explained-text';
import { ReadingGuideData, MedicalTerm } from '../../../../core/models/reading-guide';
import { explainText } from '../../../../core/utils/reading-guide';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  ClinicalResultParameter,
  ClinicalResultSection,
  ClinicalResultSet,
  StudyRecord,
} from '../../../../core/models/medical';
import { ClinicalResultsStore } from '../../../../core/services/clinical-results-store';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import {
  ClinicalResultStatus,
  clinicalCategoryKey,
  clinicalCategoryLabel,
  clinicalReferenceLabel,
  clinicalResultStatus,
  clinicalValueLabel,
} from '../../../../core/utils/clinical-results';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { ResultRangeChart } from '../../components/result-range-chart/result-range-chart';
import { ResultTrendChart } from '../../components/result-trend-chart/result-trend-chart';

interface ResultStudyView {
  id: string;
  name: string;
  category: string;
  categoryLabel: string;
  performedAt: string | null;
  parameters: ClinicalResultParameter[];
  presentation: string;
  material: string | null;
  comment: string | null;
  sections: ClinicalResultSection[];
  source: StudyRecord;
}

interface ResultFilter {
  id: string;
  label: string;
  count: number;
}

@Component({
  selector: 'app-patient-results',
  imports: [ReadingGuide, ExplainedText, AppIcon, ResultRangeChart, ResultTrendChart],
  templateUrl: './patient-results.html',
  styleUrl: './patient-results.scss',
})
export class PatientResults {
  protected readonly guide = signal<ReadingGuideData | null>(null);
  protected readonly explanationsEnabled = signal(true);
  protected resultText(study: ResultStudyView): string {
    return [...study.sections.map(s => `${s.title}\n${s.content}`), study.comment ?? ''].filter(Boolean).join('\n\n');
  }
  protected readonly explained = (text: string) => explainText(text, this.guide(), this.selectedStudy()?.id ?? '');
  protected readonly terms = computed(() => this.guide()?.documentId === this.selectedStudy()?.id ? this.guide()?.terms ?? [] : []);
  protected askTerm(event: {term: MedicalTerm; quote: string}): void {
    const study = this.selectedStudy(); if (!study) return;
    this.workspace.visitQuestionDraft.set({studyId: study.id, question: `Co oznacza „${event.term.name}” w kontekście mojego wyniku?`,
      termId: event.term.id, sourceQuote: event.quote});
    this.workspace.selectView('visits'); void this.router?.navigateByUrl('/');
  }
  protected readonly workspace = inject(WorkspaceStore);
  private readonly clinicalResults = inject(ClinicalResultsStore);
  private readonly router = inject(Router, { optional: true });

  protected readonly activeFilter = signal('all');
  protected readonly selectedStudyId = signal<string | null>(null);
  protected readonly selectedParameterId = signal<string | null>(null);
  protected readonly expandedStudies = signal<ReadonlySet<string>>(new Set());

  protected readonly studies = computed<ResultStudyView[]>(() => {
    const mapped = (this.workspace.data()?.studies ?? [])
      .map((study) => ({ study, result: study.resultSet ?? this.clinicalResults.resultSetFor(study.id) }))
      .filter(({ result }) => Boolean(result))
      .map(({ study, result }) => this.toView(study, result))
      .sort((left, right) => timestamp(right.performedAt) - timestamp(left.performedAt));

    return addParameterHistory(mapped);
  });

  protected readonly resolvingResults = computed(() =>
    (this.workspace.data()?.studies ?? []).some((study) => this.clinicalResults.isPending(study)),
  );

  protected readonly filters = computed<ResultFilter[]>(() => {
    const studies = this.studies();
    const categories = new Map<string, ResultFilter>();
    for (const study of studies) {
      const current = categories.get(study.category);
      categories.set(study.category, {
        id: study.category,
        label: study.categoryLabel,
        count: (current?.count ?? 0) + 1,
      });
    }
    return [
      { id: 'all', label: 'Wszystkie', count: studies.length },
      ...Array.from(categories.values()).sort((left, right) => left.label.localeCompare(right.label, 'pl')),
    ];
  });

  protected readonly filteredStudies = computed(() => {
    const category = this.activeFilter();
    return category === 'all' ? this.studies() : this.studies().filter((study) => study.category === category);
  });

  protected readonly selectedStudy = computed(() =>
    this.filteredStudies().find((study) => study.id === this.selectedStudyId()) ?? null,
  );

  protected readonly selectedParameter = computed(() =>
    this.selectedStudy()?.parameters.find((parameter) => parameter.id === this.selectedParameterId()) ?? null,
  );

  constructor() {
    effect(() => {
      const studies = this.filteredStudies();
      const selected = studies.find((study) => study.id === this.selectedStudyId()) ?? studies[0] ?? null;
      if (selected?.id !== this.selectedStudyId()) this.selectedStudyId.set(selected?.id ?? null);

      const parameters = selected?.parameters ?? [];
      const parameter = parameters.find((item) => item.id === this.selectedParameterId()) ?? parameters[0] ?? null;
      if (parameter?.id !== this.selectedParameterId()) this.selectedParameterId.set(parameter?.id ?? null);
    });
  }

  protected setFilter(filterId: string): void {
    this.activeFilter.set(filterId);
  }

  protected selectStudy(study: ResultStudyView): void {
    this.selectedStudyId.set(study.id);
    this.selectedParameterId.set(study.parameters[0]?.id ?? null);
  }

  protected selectParameter(study: ResultStudyView, parameter: ClinicalResultParameter): void {
    this.selectedStudyId.set(study.id);
    this.selectedParameterId.set(parameter.id);
  }

  protected visibleParameters(study: ResultStudyView): ClinicalResultParameter[] {
    return this.expandedStudies().has(study.id) ? study.parameters : study.parameters.slice(0, 5);
  }

  protected toggleExpanded(studyId: string): void {
    const next = new Set(this.expandedStudies());
    next.has(studyId) ? next.delete(studyId) : next.add(studyId);
    this.expandedStudies.set(next);
  }

  protected status(parameter: ClinicalResultParameter): ClinicalResultStatus {
    return clinicalResultStatus(this.withConfirmedRange(parameter));
  }

  protected value(parameter: ClinicalResultParameter): string {
    return clinicalValueLabel(parameter);
  }

  protected reference(parameter: ClinicalResultParameter): string {
    return parameter.referenceSource === 'document'
      ? clinicalReferenceLabel(parameter.referenceRange, parameter.unit)
      : 'Nie potwierdzono zakresu z tego dokumentu';
  }

  protected statusCopy(parameter: ClinicalResultParameter): string {
    return {
      low: 'Ta wartość jest poniżej zakresu podanego na tym wyniku.',
      normal: 'Ta wartość mieści się w zakresie podanym na tym wyniku.',
      high: 'Ta wartość jest powyżej zakresu podanego na tym wyniku.',
      abnormal: 'Laboratorium oznaczyło tę wartość jako poza zakresem.',
      unknown: 'Nie można wiarygodnie porównać tej wartości z zakresem z dokumentu.',
    }[this.status(parameter)];
  }

  protected count(study: ResultStudyView, kind: 'normal' | 'attention' | 'unknown'): number {
    return study.parameters.filter((parameter) => {
      const status = this.status(parameter);
      if (kind === 'attention') return ['low', 'high', 'abnormal'].includes(status);
      return status === kind;
    }).length;
  }

  protected date(value: string | null): string {
    return value
      ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' }).format(new Date(value))
      : 'Brak daty badania';
  }

  protected hasTrend(parameter: ClinicalResultParameter): boolean {
    return new Set(this.history(parameter).map((point) => point.observedAt)).size > 1;
  }

  protected history(parameter: ClinicalResultParameter) {
    return parameter.history ?? [];
  }

  protected rangeChanged(parameter: ClinicalResultParameter): boolean {
    return this.history(parameter).some((point) =>
      point.referenceRange && JSON.stringify(point.referenceRange) !== JSON.stringify(parameter.referenceRange));
  }

  protected canDrawTrend(parameter: ClinicalResultParameter): boolean {
    return this.hasTrend(parameter) && !this.rangeChanged(parameter)
      && this.history(parameter).every((point) => !!point.referenceRange);
  }

  protected provenance(parameter: ClinicalResultParameter): string {
    return {
      laboratory: 'Wartość przekazana przez laboratorium',
      ocr: 'Wartość odczytana przez OCR',
      manual: 'Wartość wpisana ręcznie',
      doctor_approved: 'Opis zatwierdzony przez lekarza',
      document: 'Wartość odczytana z dokumentu; sposób odczytu niepotwierdzony',
    }[parameter.sourceKind ?? 'document'];
  }

  protected needsSourceCheck(parameter: ClinicalResultParameter): boolean {
    return parameter.sourceKind === 'ocr' && (parameter.confidence == null || parameter.confidence < 0.9);
  }

  protected explanation(parameter: ClinicalResultParameter): string | null {
    const key = (parameter.code || parameter.name).toLocaleLowerCase('pl').replace(/[^a-z0-9]/g, '');
    return PARAMETER_EXPLANATIONS[key] ?? null;
  }

  protected reportSections(study: ResultStudyView, kind: 'conclusion' | 'recommendations' | 'other') {
    return study.sections.filter((section) => {
      const title = section.title.toLocaleLowerCase('pl');
      const conclusion = /wnios|rozpozn|podsum|bi-rads/.test(title);
      const recommendations = /zalec/.test(title);
      return kind === 'conclusion' ? conclusion : kind === 'recommendations' ? recommendations : !conclusion && !recommendations;
    });
  }

  private withConfirmedRange(parameter: ClinicalResultParameter): ClinicalResultParameter {
    return parameter.referenceSource === 'document' ? parameter : {
      ...parameter, referenceRange: null, interpretation: null,
    };
  }

  protected isReport(study: ResultStudyView): boolean {
    return study.presentation === 'report' || study.sections.length > 0;
  }

  protected sectionTone(section: ClinicalResultSection): 'key' | 'standard' {
    return /(wnios|rozpozn|zalec|podsum|bi-rads)/i.test(section.title) ? 'key' : 'standard';
  }

  protected addResult(): void {
    void this.router?.navigateByUrl('/documents/upload');
  }

  protected openSource(study: ResultStudyView): void {
    if (study.source.primaryAssetId) {
      this.workspace.download(study.source.primaryAssetId, study.source.originalFilename ?? 'dokument');
      return;
    }
    void this.router?.navigate(['/documents', study.source.id]);
  }

  protected askDoctor(study: ResultStudyView, parameter: ClinicalResultParameter): void {
    this.workspace.visitQuestionDraft.set({
      studyId: study.id,
      question: `Chcę zapytać o wynik ${parameter.name} z badania ${study.name} (${this.date(study.performedAt)}).`,
    });
    this.workspace.selectView('visits');
  }

  private toView(study: StudyRecord, result: ClinicalResultSet | null): ResultStudyView {
    const category = clinicalCategoryKey(result?.category);
    return {
      id: study.id,
      name: result?.name?.trim() || humanTitle(study.title),
      category,
      categoryLabel: clinicalCategoryLabel(category, result?.categoryLabel),
      performedAt: study.performedAt,
      parameters: result?.parameters ?? [],
      presentation: result?.presentation ?? 'parameters',
      material: result?.material ?? null,
      comment: result?.comment ?? null,
      sections: result?.sections ?? [],
      source: study,
    };
  }
}

function timestamp(value: string | null): number {
  return value ? new Date(value).getTime() : 0;
}

function humanTitle(value: string): string {
  return value.replace(/^\d+[_\s-]*/, '').replace(/_/g, ' ').replace(/^./, (letter) => letter.toLocaleUpperCase('pl'));
}

function addParameterHistory(studies: ResultStudyView[]): ResultStudyView[] {
  const points = new Map<string, NonNullable<ClinicalResultParameter['history']>>();

  for (const study of studies) {
    for (const parameter of study.parameters) {
      const value = parameter.numericValue ?? (typeof parameter.value === 'number' ? parameter.value : null);
      const observedAt = study.performedAt ?? parameter.observedAt;
      if (value == null || !observedAt) continue;
      const key = parameterKey(parameter);
      if (!key) continue;
      points.set(key, [...(points.get(key) ?? []), {
        observedAt, value, unit: parameter.unit, method: parameter.method,
        parameterCode: parameter.code, parameterName: parameter.name,
        referenceRange: parameter.referenceSource === 'document' ? parameter.referenceRange : null,
      }]);
    }
  }

  return studies.map((study) => ({
    ...study,
    parameters: study.parameters.map((parameter) => {
      const observedAt = study.performedAt ?? parameter.observedAt;
      const key = parameterKey(parameter);
      const supplied = (parameter.history ?? []).filter((point) =>
        key && point.unit === parameter.unit && point.method === parameter.method
        && point.parameterCode === parameter.code && point.parameterName === parameter.name);
      const currentValue = parameter.numericValue ?? (typeof parameter.value === 'number' ? parameter.value : null);
      const current = observedAt && currentValue != null ? [{
        observedAt, value: currentValue, unit: parameter.unit,
        method: parameter.method, parameterCode: parameter.code, parameterName: parameter.name,
        referenceRange: parameter.referenceSource === 'document' ? parameter.referenceRange : null,
      }] : [];
      const history = uniqueHistory([...supplied, ...(key ? points.get(key) ?? [] : []), ...current]);
      return { ...parameter, observedAt, history };
    }),
  }));
}

function parameterKey(parameter: ClinicalResultParameter): string | null {
  if (!parameter.code?.trim() || !parameter.name.trim() || !parameter.unit?.trim() || !parameter.method?.trim()) return null;
  return `${parameter.code}|${parameter.name}|${parameter.unit}|${parameter.method}`.toLocaleLowerCase('pl');
}

function uniqueHistory(
  points: NonNullable<ClinicalResultParameter['history']>,
): NonNullable<ClinicalResultParameter['history']> {
  const unique = new Map(points.map((point) => [`${point.observedAt}:${point.value}`, point]));
  return Array.from(unique.values()).sort((left, right) => timestamp(left.observedAt) - timestamp(right.observedAt));
}

const PARAMETER_EXPLANATIONS: Record<string, string> = {
  hgb: 'Hemoglobina to białko w czerwonych krwinkach, które przenosi tlen.',
  hemoglobina: 'Hemoglobina to białko w czerwonych krwinkach, które przenosi tlen.',
  wbc: 'WBC oznacza liczbę białych krwinek w próbce krwi.',
  rbc: 'RBC oznacza liczbę czerwonych krwinek w próbce krwi.',
  plt: 'PLT oznacza liczbę płytek krwi w próbce.',
  hct: 'Hematokryt pokazuje, jaką część objętości krwi zajmują czerwone krwinki.',
  mcv: 'MCV opisuje średnią objętość czerwonej krwinki.',
  mch: 'MCH opisuje średnią ilość hemoglobiny w czerwonej krwince.',
  mchc: 'MCHC opisuje średnie stężenie hemoglobiny w czerwonych krwinkach.',
};

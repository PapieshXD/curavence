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
  clinicalStatusCopy,
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
  imports: [AppIcon, ResultRangeChart, ResultTrendChart],
  templateUrl: './patient-results.html',
  styleUrl: './patient-results.scss',
})
export class PatientResults {
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
    return clinicalResultStatus(parameter);
  }

  protected value(parameter: ClinicalResultParameter): string {
    return clinicalValueLabel(parameter);
  }

  protected reference(parameter: ClinicalResultParameter): string {
    return clinicalReferenceLabel(parameter.referenceRange, parameter.unit);
  }

  protected statusCopy(parameter: ClinicalResultParameter): string {
    return clinicalStatusCopy(clinicalResultStatus(parameter));
  }

  protected count(study: ResultStudyView, kind: 'normal' | 'attention' | 'unknown'): number {
    return study.parameters.filter((parameter) => {
      const status = clinicalResultStatus(parameter);
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
    const historyDates = new Set((parameter.history ?? []).map((point) => point.observedAt));
    if (parameter.observedAt) historyDates.add(parameter.observedAt);
    return historyDates.size > 1;
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
    void this.router?.navigate(['/documents', study.source.id]);
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
  const points = new Map<string, Array<{ observedAt: string; value: number }>>();

  for (const study of studies) {
    for (const parameter of study.parameters) {
      const value = parameter.numericValue ?? (typeof parameter.value === 'number' ? parameter.value : null);
      const observedAt = study.performedAt ?? parameter.observedAt;
      if (value == null || !observedAt) continue;
      const key = parameterKey(parameter);
      points.set(key, [...(points.get(key) ?? []), { observedAt, value }]);
    }
  }

  return studies.map((study) => ({
    ...study,
    parameters: study.parameters.map((parameter) => {
      const observedAt = study.performedAt ?? parameter.observedAt;
      const history = uniqueHistory([...(parameter.history ?? []), ...(points.get(parameterKey(parameter)) ?? [])]);
      return { ...parameter, observedAt, history };
    }),
  }));
}

function parameterKey(parameter: ClinicalResultParameter): string {
  const identity = parameter.code?.trim() || parameter.name.trim();
  return `${identity}|${parameter.unit ?? ''}`.toLocaleLowerCase('pl');
}

function uniqueHistory(
  points: Array<{ observedAt: string; value: number }>,
): Array<{ observedAt: string; value: number }> {
  const unique = new Map(points.map((point) => [`${point.observedAt}:${point.value}`, point]));
  return Array.from(unique.values()).sort((left, right) => timestamp(left.observedAt) - timestamp(right.observedAt));
}

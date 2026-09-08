import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';

type TimelineKind = 'study' | 'document' | 'sharing';

interface HealthEvent {
  id: string;
  date: string;
  kind: TimelineKind;
  category: string;
  title: string;
  source: string;
  description: string;
  note?: string;
  action: string;
  icon: string;
  studyType?: string;
}

@Component({
  selector: 'app-patient-timeline',
  imports: [AppIcon],
  templateUrl: './patient-timeline.html',
  styleUrl: './patient-timeline.scss',
})
export class PatientTimeline {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router, { optional: true });
  protected readonly filter = signal<'all' | TimelineKind>('all');

  protected readonly events = computed<HealthEvent[]>(() => {
    const studies = (this.workspace.data()?.studies ?? []).map((study): HealthEvent => {
      const imaging = ['MRI', 'CT', 'XRAY', 'ULTRASOUND'].includes(study.typeCode);
      const laboratory = study.typeCode === 'LABORATORY';
      return {
        id: study.id,
        date: study.performedAt ?? '',
        kind: laboratory || imaging ? 'study' : 'document',
        category: laboratory ? 'Badania krwi' : imaging ? study.typeName : 'Dokument medyczny',
        title: this.consumerTitle(study.title, study.typeCode, study.typeName),
        source: study.sourceName || 'Dodano samodzielnie',
        description: laboratory
          ? 'Wyniki są gotowe do sprawdzenia'
          : imaging
            ? (study.clinicalStatus === 'SIGNED' ? 'Opis badania dostępny' : 'Obrazy są dostępne')
            : (study.description || 'Dokument zapisany w Twojej historii'),
        action: laboratory ? 'Zobacz wyniki' : imaging ? 'Zobacz badanie' : 'Zobacz dokument',
        icon: laboratory ? 'lab' : imaging ? 'scan' : 'document',
        studyType: study.typeCode,
      };
    });
    const grants = (this.workspace.data()?.grants ?? []).map((grant): HealthEvent => ({
      id: grant.id,
      date: grant.createdAt,
      kind: 'sharing',
      category: 'Udostępnienie',
      title: 'Udostępniono dokumentację',
      source: grant.recipientName,
      description: this.scopeLabel(grant.scope),
      note: grant.expiresAt ? `Dostęp ważny do ${this.date(grant.expiresAt)}` : 'Dostęp bezterminowy',
      action: 'Zarządzaj dostępem',
      icon: 'share',
    }));
    return [...studies, ...grants]
      .filter((event) => this.filter() === 'all' || event.kind === this.filter())
      .sort((a, b) => b.date.localeCompare(a.date));
  });

  protected date(value: string | null): string {
    return value
      ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' }).format(new Date(value))
      : 'Brak daty';
  }

  protected openEvent(event: HealthEvent): void {
    if (event.kind === 'sharing') { this.workspace.selectView('sharing'); return; }
    if (event.studyType === 'LABORATORY') { this.workspace.selectView('results'); return; }
    if (event.kind === 'document') { this.workspace.selectView('documents'); return; }
    const patientId = this.workspace.data()?.profile?.patientId;
    if (patientId) void this.router?.navigate(['/patients', patientId, 'imaging']);
  }

  private scopeLabel(scope: string): string {
    return {
      FULL_STUDY: 'Badania laboratoryjne i obrazowe',
      LAB_RESULTS: 'Badania laboratoryjne',
      DIAGNOSTIC_REPORT: 'Opisy badań',
      REHABILITATION_SUMMARY: 'Rehabilitacja i wskazane badania',
      SELECTED_STUDIES: 'Wybrane badania',
    }[scope] ?? 'Wybrane dane medyczne';
  }

  private consumerTitle(title: string, typeCode: string, typeName: string): string {
    if (typeCode === 'LABORATORY' && /kontrolne wyniki/i.test(title)) return 'Morfologia krwi';
    if (['MRI', 'CT', 'XRAY', 'ULTRASOUND'].includes(typeCode)) return typeName;
    return title.replace(/^\d+[_\s-]*/, '').replace(/_/g, ' ').replace(/^./, (letter) => letter.toLocaleUpperCase('pl'));
  }
}

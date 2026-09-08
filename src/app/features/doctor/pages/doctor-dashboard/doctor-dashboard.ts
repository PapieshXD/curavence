import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AccessGrantRecord, StudyRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';

@Component({
  selector: 'app-doctor-dashboard',
  imports: [FormsModule, AppIcon],
  templateUrl: './doctor-dashboard.html',
  styleUrl: './doctor-dashboard.scss',
})
export class DoctorDashboard {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router, { optional: true });
  protected readonly selectedId = signal('');
  protected readonly summaryText = signal('');
  protected readonly conclusion = signal('');
  protected readonly saving = signal(false);
  protected readonly imageIndex = signal(42);
  protected readonly caseTab = signal<'overview' | 'images' | 'documents' | 'history'>('overview');

  protected readonly studies = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.studies ?? []).filter((study) =>
      !query || `${study.patientName} ${study.title} ${study.typeName}`.toLocaleLowerCase('pl').includes(query));
  });
  protected readonly needsReview = computed(() => this.studies().filter((study) => study.clinicalStatus !== 'SIGNED'));
  protected readonly recentlyOpened = computed(() => this.studies().filter((study) => study.clinicalStatus === 'SIGNED'));
  protected readonly selected = computed<StudyRecord | null>(() => this.studies().find((study) => study.id === this.selectedId()) ?? this.studies()[0] ?? null);
  protected readonly activeGrant = computed<AccessGrantRecord | null>(() => {
    const study = this.selected();
    return this.workspace.data()?.grants.find((grant) => grant.patientId === study?.patientId && grant.status === 'ACTIVE') ?? null;
  });

  protected selectStudy(study: StudyRecord): void {
    this.selectedId.set(study.id);
    this.caseTab.set('overview');
    this.summaryText.set('');
    this.conclusion.set('');
  }

  protected initials(name: string): string {
    return name.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase();
  }

  protected date(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Brak daty';
  }

  protected queueReason(study: StudyRecord): string {
    if (study.clinicalStatus === 'AWAITING_REVIEW') return 'Wymaga opisu specjalisty';
    if (study.clinicalStatus === 'SIGNED') return 'Opis podpisany';
    return 'Sprawdź kompletność badania';
  }

  protected openPatient(): void {
    this.workspace.selectView('patients');
  }

  protected openViewer(): void {
    const study = this.selected();
    if (study) void this.router?.navigate(['/imaging-studies', study.id, 'viewer']);
  }

  protected showSuggestedImage(): void {
    this.imageIndex.set(42);
    this.caseTab.set('images');
    this.workspace.notify('Przejście do obrazu 42 — wskazanie wymaga oceny specjalisty.');
  }

  protected startReview(): void {
    document.getElementById('clinical-review')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  protected shareResult(): void {
    this.workspace.notify('Wynik będzie widoczny pacjentowi po podpisaniu opisu.');
  }

  protected saveReview(signed: boolean): void {
    const study = this.selected();
    if (!study || !this.summaryText().trim()) {
      this.workspace.notify('Wybierz badanie i wpisz podsumowanie.');
      return;
    }
    this.saving.set(true);
    this.workspace.addReview(study.id, this.summaryText(), this.conclusion(), signed).subscribe({
      next: () => {
        this.saving.set(false);
        this.summaryText.set('');
        this.conclusion.set('');
        this.workspace.notify(signed ? 'Opis został podpisany i dodany do historii pacjenta.' : 'Szkic opisu został zapisany.');
      },
      error: (error) => {
        this.saving.set(false);
        this.workspace.notify(error?.error?.message ?? 'Nie udało się zapisać opisu.');
      },
    });
  }
}

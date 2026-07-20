import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { SummaryItem, SummaryStrip } from '../../../../shared/components/summary-strip/summary-strip';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-doctor-dashboard', imports: [FormsModule, AppIcon, StatusText, SummaryStrip, EnumLabelPipe], templateUrl: './doctor-dashboard.html', styleUrl: './doctor-dashboard.scss' })
export class DoctorDashboard {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly selectedId = signal('');
  protected readonly summaryText = signal('');
  protected readonly conclusion = signal('');
  protected readonly saving = signal(false);
  protected readonly summary = computed<SummaryItem[]>(() => {
    const data = this.workspace.data();
    return [{ label: 'Udostępnieni pacjenci', value: String(data?.overview.patients ?? 0) }, { label: 'Widoczne badania', value: String(data?.overview.studies ?? 0) }, { label: 'Do weryfikacji', value: String(data?.overview.awaitingReview ?? 0), tone: 'warning' }, { label: 'Opisy kliniczne', value: String(data?.reviews.length ?? 0) }];
  });
  protected readonly studies = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.studies ?? []).filter((study) => !query || `${study.patientName} ${study.title} ${study.typeName}`.toLocaleLowerCase('pl').includes(query));
  });
  protected readonly selected = computed(() => this.studies().find((study) => study.id === this.selectedId()) ?? this.studies()[0] ?? null);
  protected initials(name: string): string { return name.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase(); }
  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Brak daty'; }
  protected clinicalTone(value: string): 'success' | 'warning' | 'neutral' { return value === 'SIGNED' ? 'success' : value === 'AWAITING_REVIEW' ? 'warning' : 'neutral'; }
  protected openPatient(): void { this.workspace.selectView('patients'); }
  protected saveReview(signed: boolean): void {
    const study = this.selected();
    if (!study || !this.summaryText().trim()) { this.workspace.notify('Wybierz badanie i wpisz podsumowanie.'); return; }
    this.saving.set(true);
    this.workspace.addReview(study.id, this.summaryText(), this.conclusion(), signed).subscribe({
      next: () => { this.saving.set(false); this.summaryText.set(''); this.conclusion.set(''); this.workspace.notify(signed ? 'Opis został podpisany.' : 'Szkic opisu został zapisany.'); },
      error: (error) => { this.saving.set(false); this.workspace.notify(error?.error?.message ?? 'Nie udało się zapisać opisu.'); },
    });
  }
}

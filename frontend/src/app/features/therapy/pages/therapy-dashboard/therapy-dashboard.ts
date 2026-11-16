import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccessGrantRecord, PatientRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';

@Component({
  selector: 'app-therapy-dashboard',
  imports: [FormsModule, AppIcon],
  templateUrl: './therapy-dashboard.html',
  styleUrl: './therapy-dashboard.scss',
})
export class TherapyDashboard {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly selectedId = signal('');
  protected readonly activeTab = signal<'overview' | 'shared' | 'visits' | 'messages'>('overview');
  protected readonly note = signal('');
  protected readonly savedNote = signal('');
  protected readonly accessRequested = signal(false);

  protected readonly patients = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.patients ?? []).filter((patient) =>
      !query || `${patient.firstName} ${patient.lastName}`.toLocaleLowerCase('pl').includes(query));
  });
  protected readonly selected = computed<PatientRecord | null>(() => this.patients().find((patient) => patient.id === this.selectedId()) ?? this.patients()[0] ?? null);
  protected readonly studies = computed(() => [...(this.workspace.data()?.studies.filter((study) => study.patientId === this.selected()?.id) ?? [])]
    .sort((left, right) => (right.performedAt ?? '').localeCompare(left.performedAt ?? '')));
  protected readonly grants = computed(() => this.workspace.data()?.grants.filter((grant) => grant.patientId === this.selected()?.id) ?? []);
  protected readonly activeGrant = computed<AccessGrantRecord | null>(() => this.grants().find((grant) => grant.status === 'ACTIVE') ?? this.grants()[0] ?? null);

  protected selectPatient(patient: PatientRecord): void {
    this.selectedId.set(patient.id);
    this.activeTab.set('overview');
    this.note.set('');
    this.savedNote.set('');
    this.accessRequested.set(false);
  }

  protected initials(first: string, last: string): string {
    return `${first[0]}${last[0]}`.toUpperCase();
  }

  protected date(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Brak daty';
  }

  protected patientGoal(): string {
    return this.activeGrant()?.purpose || 'Bezpieczny powrót do codziennej aktywności';
  }

  protected messagePatient(): void {
    this.activeTab.set('messages');
    this.workspace.notify('Otworzono bezpieczny kanał wiadomości z pacjentem.');
  }

  protected saveNote(): void {
    const value = this.note().trim();
    if (!value) {
      this.workspace.notify('Wpisz notatkę z dzisiejszej wizyty.');
      return;
    }
    this.savedNote.set(value);
    this.note.set('');
    this.workspace.notify('Notatka została zapisana w planie opieki.');
  }

  protected requestAccess(): void {
    this.accessRequested.set(true);
    this.workspace.notify('Prośba o dodatkowy zakres danych została wysłana pacjentowi.');
  }
}

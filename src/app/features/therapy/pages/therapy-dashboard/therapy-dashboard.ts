import { Component, computed, inject, signal } from '@angular/core';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { SummaryItem, SummaryStrip } from '../../../../shared/components/summary-strip/summary-strip';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-therapy-dashboard', imports: [StatusText, SummaryStrip, EnumLabelPipe], templateUrl: './therapy-dashboard.html' })
export class TherapyDashboard {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly selectedId = signal('');
  protected readonly summary = computed<SummaryItem[]>(() => {
    const data = this.workspace.data();
    return [{ label: 'Udostępnieni pacjenci', value: String(data?.patients.length ?? 0) }, { label: 'Widoczne badania', value: String(data?.studies.length ?? 0) }, { label: 'Aktywne zakresy', value: String(data?.grants.filter((grant) => grant.status === 'ACTIVE').length ?? 0) }, { label: 'Zdarzenia audytowe', value: String(data?.auditEvents.length ?? 0) }];
  });
  protected readonly selected = computed(() => this.workspace.data()?.patients.find((patient) => patient.id === this.selectedId()) ?? this.workspace.data()?.patients[0] ?? null);
  protected readonly studies = computed(() => this.workspace.data()?.studies.filter((study) => study.patientId === this.selected()?.id) ?? []);
  protected readonly grants = computed(() => this.workspace.data()?.grants.filter((grant) => grant.patientId === this.selected()?.id) ?? []);
  protected initials(first: string, last: string): string { return `${first[0]}${last[0]}`.toUpperCase(); }
  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Brak daty'; }
}

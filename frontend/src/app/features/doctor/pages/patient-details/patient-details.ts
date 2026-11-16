import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { PatientRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-patient-details', imports: [StatusText, EnumLabelPipe], templateUrl: './patient-details.html', styleUrl: './patient-details.scss' })
export class PatientDetails {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router, { optional: true });
  protected readonly selectedId = this.workspace.selectedPatientId;
  protected readonly patients = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.patients ?? []).filter((patient) => !query || `${patient.firstName} ${patient.lastName} ${patient.email}`.toLocaleLowerCase('pl').includes(query));
  });
  protected readonly selected = computed<PatientRecord | null>(() => this.patients().find((patient) => patient.id === this.selectedId()) ?? this.patients()[0] ?? null);
  protected readonly studies = computed(() => this.workspace.data()?.studies.filter((study) => study.patientId === this.selected()?.id) ?? []);
  protected openStudy(id: string): void {
    if (this.workspace.role() !== 'doctor') {
      const study = this.studies().find(item => item.id === id);
      if (study?.imagingStudyId) void this.router?.navigate(['/imaging-studies', study.imagingStudyId]);
      else if (study?.primaryAssetId) void this.router?.navigate(['/documents', id]);
      else this.workspace.notify('Do tego badania nie dołączono pliku źródłowego.');
      return;
    }
    this.workspace.selectedStudyId.set(id);
    this.workspace.search.set('');
    this.workspace.selectView('overview');
  }
  protected initials(firstName: string, lastName: string): string { return `${firstName[0]}${lastName[0]}`.toUpperCase(); }
  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Nie podano'; }
}

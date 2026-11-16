import { KnowledgePanel } from '../../knowledge-panel';
import { ExportPanel } from '../../export-panel';
import { Readiness } from '../../../../core/services/readiness';
import { DocumentLibrary } from '../../document-library';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { StudyRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StudyPreview } from '../../../../shared/components/study-preview/study-preview';
import { PATIENT_STUDY_FILTERS, PatientStudyFilter, patientStudyFilter, patientStudyStatus, patientStudyTitle } from '../../../patient/patient-study';

@Component({ selector: 'app-document-list', imports: [KnowledgePanel, ExportPanel, DocumentLibrary, FormsModule, AppIcon, StudyPreview], templateUrl: './document-list.html', styleUrl: './document-list.scss' })
export class DocumentList {
  readonly readiness=inject(Readiness);
  readonly exportSelection=signal<string[]>([]);
  selectExport(id:string,selected:boolean){this.exportSelection.update(ids=>selected?[...new Set([...ids,id])]:ids.filter(s=>s!==id));}
  protected readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router, { optional: true });
  protected readonly category = signal('all');
  protected readonly patientCategory = signal<PatientStudyFilter>('all');
  protected readonly patientFilters = PATIENT_STUDY_FILTERS;
  protected readonly query = signal('');
  protected readonly view = signal<'list' | 'table'>('list');
  protected readonly categories = computed(() => {
    const types = new Map<string,string>();
    for (const study of this.workspace.data()?.studies ?? []) types.set(study.typeCode, study.typeName);
    return [...types].map(([code,name])=>({code,name})).sort((a,b)=>a.name.localeCompare(b.name,'pl'));
  });
  protected readonly selectedId = signal('');
  protected readonly canUpload = computed(() => this.workspace.data()?.session.superuser || ['patient', 'admin'].includes(this.workspace.role()));
  protected readonly documents = computed(() => {
    const query = `${this.query()} ${this.workspace.search()}`.trim().toLocaleLowerCase('pl');
    return [...(this.workspace.data()?.studies ?? [])].filter((study) =>
      (this.category() === 'all' || study.typeCode === this.category()) &&
      (!query || `${study.title} ${study.patientName} ${study.typeName} ${study.sourceName} ${study.originalFilename ?? ''}`.toLocaleLowerCase('pl').includes(query)))
      .sort((a,b)=>(b.performedAt??'').localeCompare(a.performedAt??''));
  });
  protected readonly selected = computed(() => {
    const items = this.documents();
    return items.find((item) => item.id === this.selectedId()) ?? items[0] ?? null;
  });
  protected readonly patientStudies = computed(() => this.documents().filter(study =>
    this.patientCategory() === 'all' || patientStudyFilter(study) === this.patientCategory()));

  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Brak daty'; }
  protected size(value: number | null): string { if (value == null) return 'Brak pliku'; return value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`; }
  protected download(assetId: string | null, filename: string | null): void { assetId ? this.workspace.download(assetId, filename ?? 'dokument') : this.workspace.notify('Badanie nie ma jeszcze pliku.'); }

  protected note(study: StudyRecord): string {
    if (this.workspace.role() === 'patient') return patientStudyStatus(study);
    if (study.ingestionStatus === 'FAILED') return 'Wymaga sprawdzenia';
    if (study.ingestionStatus !== 'READY') return 'Przetwarzanie';
    return study.imagingStudyId ? 'Obrazy dostępne' : study.primaryAssetId ? 'Plik dostępny' : 'Bez załącznika';
  }
  protected format(study: StudyRecord): string { return study.imagingStudyId ? 'Obrazy DICOM' : study.mimeType === 'application/pdf' ? 'PDF' : study.originalFilename?.split('.').pop()?.toUpperCase() || 'Wpis'; }
  protected clearFilters(): void { this.category.set('all'); this.query.set(''); this.workspace.search.set(''); }
  protected clearPatientFilters(): void { this.patientCategory.set('all'); this.clearFilters(); }
  protected patientTitle(study: StudyRecord): string { return patientStudyTitle(study); }
  protected patientStatus(study: StudyRecord): string { return patientStudyStatus(study); }

  protected displayTitle(title: string): string {
    return title.replace(/^\d+[_\s-]*/, '').replace(/_/g, ' ').replace(/^./, (letter) => letter.toLocaleUpperCase('pl'));
  }

  protected openDocument(study: StudyRecord): void {
    if (this.workspace.role() === 'patient') this.workspace.studyReturnView.set('results');
    void this.router?.navigate(study.imagingStudyId ? ['/imaging-studies', study.imagingStudyId, 'viewer'] : ['/documents', study.id]);
  }

  protected openUpload(): void {
    if (this.workspace.role() === 'patient') this.workspace.studyReturnView.set('results');
    void this.router?.navigateByUrl('/documents/upload');
  }
}

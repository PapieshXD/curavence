import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { AccessGrantRecord, StudyRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { Api } from '../../../../core/services/api';
import { ImagingAnalysis, ImagingStudyDetails } from '../../../../core/models/imaging';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';
import { PatientImageGallery } from '../../../imaging/components/patient-image-gallery/patient-image-gallery';
import { DocumentDetails } from '../../../documents/pages/document-details/document-details';
import { RichReviewChange, RichReviewEditor } from '../../../../shared/components/rich-review-editor/rich-review-editor';

@Component({
  selector: 'app-doctor-dashboard',
  imports: [AppIcon, EnumLabelPipe, PatientImageGallery, DocumentDetails, RichReviewEditor],
  templateUrl: './doctor-dashboard.html',
  styleUrl: './doctor-dashboard.scss',
})
export class DoctorDashboard {
  protected readonly increment = (value: number) => value + 1;
  protected readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router, { optional: true });
  private readonly api = inject(Api);
  protected readonly selectedId = this.workspace.selectedStudyId;
  protected readonly queueFilter = signal<'all' | 'review' | 'signed'>('all');
  protected readonly imaging = signal<ImagingStudyDetails | null>(null);
  protected readonly analysis = signal<ImagingAnalysis | null>(null);
  protected readonly imagingLoading = signal(false);
  protected readonly imagingError = signal('');
  protected readonly analysisLoading = signal(false);
  protected readonly analysisError = signal('');
  protected readonly reload = signal(0);
  protected readonly summaryText = signal('');
  protected readonly conclusion = signal('');
  protected readonly summaryHtml = signal('');
  protected readonly conclusionHtml = signal('');
  protected readonly saving = signal(false);
  protected readonly rewriting = signal(false);
  protected readonly rewriteError = signal('');
  protected readonly rewriteSuggestion = signal<{ summary: string; conclusion: string } | null>(null);
  private rewriteRequestId = 0;
  protected readonly caseTab = signal<'overview' | 'images' | 'documents' | 'history'>('overview');

  protected readonly studies = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.studies ?? []).filter((study) =>
      !query || `${study.patientName} ${study.title} ${study.typeName}`.toLocaleLowerCase('pl').includes(query));
  });
  protected readonly needsReview = computed(() => this.studies().filter((study) => study.clinicalStatus !== 'SIGNED'));
  protected readonly signedStudies = computed(() => this.studies().filter((study) => study.clinicalStatus === 'SIGNED'));
  protected readonly filteredStudies = computed(() => this.studies().filter(study => this.queueFilter() === 'all' || (this.queueFilter() === 'signed' ? study.clinicalStatus === 'SIGNED' : study.clinicalStatus !== 'SIGNED')));
  protected readonly selected = computed<StudyRecord | null>(() => this.filteredStudies().find((study) => study.id === this.selectedId()) ?? this.filteredStudies()[0] ?? null);
  private readonly currentStudyId = computed(() => this.selected()?.id);
  private readonly currentImagingId = computed(() => this.selected()?.imagingStudyId);
  private readonly drafts = new Map<string, { summary: string; conclusion: string; summaryHtml: string; conclusionHtml: string }>();
  protected readonly reviews = computed(() => (this.workspace.data()?.reviews ?? []).filter(review => review.studyId === this.selected()?.id).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)));
  protected readonly audit = computed(() => {
    const study = this.selected();
    const resourceIds = [study?.id, study?.imagingStudyId, study?.primaryAssetId, ...this.reviews().map(review => review.id)];
    return (this.workspace.data()?.auditEvents ?? []).filter(event => !!event.resourceId && resourceIds.includes(event.resourceId)).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  });
  protected readonly activeGrant = computed<AccessGrantRecord | null>(() => {
    const study = this.selected();
    return this.workspace.data()?.grants.find((grant) => grant.patientId === study?.patientId && grant.status === 'ACTIVE') ?? null;
  });

  constructor() {
    effect(onCleanup => {
      const id = this.currentImagingId();
      const role = this.workspace.role();
      this.reload();
      this.imaging.set(null); this.analysis.set(null);
      this.imagingError.set(''); this.analysisError.set('');
      this.imagingLoading.set(!!id); this.analysisLoading.set(!!id);
      if (!id) return;
      const details = this.api.imagingStudy(role, id).subscribe({
        next: value => { this.imaging.set(value); this.imagingLoading.set(false); },
        error: () => { this.imagingError.set('Nie udało się pobrać obrazów tego badania.'); this.imagingLoading.set(false); },
      });
      const analysis = this.api.latestImagingAnalysis(role, id).subscribe({
        next: value => { this.analysis.set(value); this.analysisLoading.set(false); },
        error: () => { this.analysisError.set('Nie udało się pobrać analizy.'); this.analysisLoading.set(false); },
      });
      onCleanup(() => { details.unsubscribe(); analysis.unsubscribe(); });
    });
    effect(onCleanup => {
      const id = this.currentStudyId();
      this.rewriteRequestId++;
      this.rewriting.set(false);
      this.rewriteSuggestion.set(null);
      this.rewriteError.set('');
      const draft = id ? this.drafts.get(id) : undefined;
      this.summaryText.set(draft?.summary ?? '');
      this.conclusion.set(draft?.conclusion ?? '');
      this.summaryHtml.set(draft?.summaryHtml ?? '');
      this.conclusionHtml.set(draft?.conclusionHtml ?? '');
      onCleanup(() => {
        if (id) untracked(() => this.drafts.set(id, { summary: this.summaryText(), conclusion: this.conclusion(), summaryHtml: this.summaryHtml(), conclusionHtml: this.conclusionHtml() }));
      });
    });
  }

  protected openDocument(): void {
    const study = this.selected();
    if (study?.primaryAssetId) void this.router?.navigate(['/documents', study.id]);
  }

  protected selectStudy(study: StudyRecord): void {
    this.selectedId.set(study.id);
    this.caseTab.set('overview');
  }

  protected updateSummary(value: RichReviewChange): void {
    this.summaryText.set(value.text);
    this.summaryHtml.set(value.html);
    this.rewriteSuggestion.set(null);
    this.rewriteError.set('');
  }

  protected updateConclusion(value: RichReviewChange): void {
    this.conclusion.set(value.text);
    this.conclusionHtml.set(value.html);
    this.rewriteSuggestion.set(null);
    this.rewriteError.set('');
  }

  protected rewriteReview(): void {
    const study = this.selected();
    const summary = this.summaryText().trim();
    const conclusion = this.conclusion().trim();
    const summaryHtml = this.summaryHtml();
    const conclusionHtml = this.conclusionHtml();
    if (!study || (!summary && !conclusion) || this.rewriting()) return;
    const requestId = ++this.rewriteRequestId;
    this.rewriting.set(true);
    this.rewriteError.set('');
    this.rewriteSuggestion.set(null);
    this.api.rewriteReview(this.workspace.role(), study.id, summary, conclusion).subscribe({
      next: suggestion => {
        if (requestId !== this.rewriteRequestId) return;
        this.rewriting.set(false);
        if (this.selected()?.id !== study.id || this.summaryText().trim() !== summary || this.conclusion().trim() !== conclusion
          || this.summaryHtml() !== summaryHtml || this.conclusionHtml() !== conclusionHtml) {
          this.rewriteError.set('Szkic zmienił się podczas redakcji. Uruchom poprawianie ponownie.');
          return;
        }
        this.rewriteSuggestion.set(suggestion);
      },
      error: () => {
        if (requestId !== this.rewriteRequestId) return;
        this.rewriting.set(false);
        this.rewriteError.set('Nie udało się poprawić tekstu. Sprawdź dostępność lokalnego modelu i spróbuj ponownie.');
      },
    });
  }

  protected applyRewrite(): void {
    const suggestion = this.rewriteSuggestion();
    if (!suggestion) return;
    this.summaryText.set(suggestion.summary);
    this.conclusion.set(suggestion.conclusion);
    this.summaryHtml.set(this.rewrittenHtml(suggestion.summary, this.summaryHtml()));
    this.conclusionHtml.set(this.rewrittenHtml(suggestion.conclusion, this.conclusionHtml()));
    this.rewriteSuggestion.set(null);
  }

  private rewrittenHtml(text: string, previousHtml: string): string {
    const old = document.createElement('div');
    old.innerHTML = previousHtml;
    const images = [...old.querySelectorAll('img')].map(image => image.outerHTML).join('');
    const lines = text.split(/\n+/).filter(Boolean);
    const paragraphs = lines.map(line => {
      const p = document.createElement('p');
      p.textContent = line;
      return p.outerHTML;
    }).join('');
    return paragraphs + (images ? `<p>${images}</p>` : '');
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
    this.workspace.selectedPatientId.set(this.selected()?.patientId ?? '');
    this.workspace.search.set('');
    this.workspace.selectView('patients');
  }

  protected openViewer(): void {
    const study = this.selected();
    if (study?.imagingStudyId) void this.router?.navigate(['/imaging-studies', study.imagingStudyId, 'viewer']);
  }

  protected startReview(): void {
    this.caseTab.set('documents');
    window.setTimeout(() => document.getElementById('clinical-review')?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
  }

  protected saveReview(signed: boolean): void {
    const study = this.selected();
    if (!study || !this.summaryText().trim()) {
      this.workspace.notify('Wybierz badanie i wpisz podsumowanie.');
      return;
    }
    this.saving.set(true);
    const savedStudyId = study.id;
    this.workspace.addReview(study.id, this.summaryText(), this.conclusion(), signed, this.summaryHtml(), this.conclusionHtml()).subscribe({
      next: () => {
        this.saving.set(false);
        this.drafts.delete(savedStudyId);
        if (this.selected()?.id === savedStudyId) {
          this.summaryText.set('');
          this.conclusion.set('');
          this.summaryHtml.set('');
          this.conclusionHtml.set('');
        }
        this.workspace.notify(signed ? 'Opis został podpisany i dodany do historii pacjenta.' : 'Szkic opisu został zapisany.');
      },
      error: (error) => {
        this.saving.set(false);
        this.workspace.notify(error?.error?.message ?? 'Nie udało się zapisać opisu.');
      },
    });
  }
}

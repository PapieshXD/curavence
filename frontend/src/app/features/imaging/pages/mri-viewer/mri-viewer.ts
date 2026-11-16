import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, effect, untracked, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subscription, catchError, forkJoin, of, timeout } from 'rxjs';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { ImagingAnalysis, ImagingAnalysisArtifact, ImagingAnnotation, ImagingOverview, ImagingStudyDetails, ImagingStudySummary, SaveImagingAnnotation } from '../../../../core/models/imaging';
import { StudySeriesRail, isTechnicalSeries } from '../../components/study-series-rail/study-series-rail';
import { NativeDicomViewer } from '../../components/native-dicom-viewer/native-dicom-viewer';
import { PatientImageGallery } from '../../components/patient-image-gallery/patient-image-gallery';
import { patientStudyTitle } from '../../../patient/patient-study';

type ViewMode = 'study' | 'sheet' | 'slice' | 'report' | 'advanced';

@Component({
  selector: 'app-mri-viewer', imports: [AppIcon, FormsModule, NativeDicomViewer, StudySeriesRail, PatientImageGallery],
  templateUrl: './mri-viewer.html', styleUrl: './mri-viewer.scss',
})
export class MriViewer implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  @ViewChild('imageStage') private imageStage?: ElementRef<HTMLDivElement>;
  protected readonly studyId = signal('');
  protected readonly details = signal<ImagingStudyDetails | null>(null);
  protected readonly overview = signal<ImagingOverview | null>(null);
  protected readonly priorStudies = signal<ImagingStudySummary[]>([]);
  protected readonly comparisonId = signal('');
  protected readonly comparisonUrl = signal('');
  protected readonly comparisonLoading = signal(false);
  protected readonly comparisonError = signal('');
  private comparisonSubscription?: Subscription;
  protected readonly history = signal<{id: string; version: number; status: string}[]>([]);
  protected readonly feedback = signal<{findingId: string; action: string}[]>([]);
  protected readonly canStartAnalysis = signal(false);
  protected readonly preferencesReady = signal(false);
  protected readonly hiddenSeries = signal<string[]>([]);
  protected readonly scopeSelection = signal<string[]>([]);
  protected readonly clinician = computed(() => this.workspace.role() === 'doctor');
  protected readonly selectedSeriesId = signal('');
  protected readonly mode = signal<ViewMode>('study');
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly overviewFallback = signal('');
  protected readonly guideStep = signal(0);
  protected readonly imageLoading = signal(false);
  protected readonly imageError = signal('');
  protected readonly overlayError = signal('');
  protected readonly baseUrl = signal('');
  protected readonly maskUrl = signal('');
  protected readonly maskVisible = signal(true);
  protected readonly opacity = signal(42);
  protected readonly zoom = signal(100);
  protected readonly frameIndex = signal(0);
  protected readonly analysis = signal<ImagingAnalysis | null>(null);
  protected readonly analysisStarting = signal(false);
  protected readonly analysisError = signal('');
  protected readonly annotations = signal<ImagingAnnotation[]>([]);
  protected readonly note = signal('');
  protected readonly savingNote = signal(false);
  protected readonly annotationError = signal('');
  protected readonly currentSeries = computed(() => this.overview()?.series.find(s => s.id === this.selectedSeriesId()) ?? null);
  protected readonly currentFrame = computed(() => this.currentSeries()?.frames[this.frameIndex()] ?? null);
  protected readonly linkedRecord = computed(() => this.workspace.data()?.studies.find(s => s.imagingStudyId === this.studyId() || s.id === this.studyId()));
  protected readonly reviews = computed(() => this.workspace.data()?.reviews.filter(r => r.studyId === this.linkedRecord()?.id) ?? []);
  protected readonly totalFrames = computed(() => this.overview()?.series.reduce((sum, s) => sum + s.frameCount, 0) ?? this.details()?.study.instanceCount ?? 0);
  protected readonly modalityLabel = computed(() => ({ MR: 'Rezonans magnetyczny', MRI: 'Rezonans magnetyczny', CT: 'Tomografia komputerowa', US: 'USG', ECG: 'EKG', EEG: 'EEG' })[this.details()?.study.modality ?? ''] ?? 'Badanie obrazowe');
  protected readonly analysisRunning = computed(() => ['QUEUED', 'RUNNING'].includes(this.analysis()?.status ?? ''));
  private readonly subscriptions = new Subscription();
  private contextSubscriptions = new Subscription();
  private overviewSubscription?: Subscription;
  private imagesSubscription?: Subscription;
  private pollTimer?: ReturnType<typeof setTimeout>;
  private readonly images = new Map<string, string>();
  private imageGeneration = 0;

  private previousRole = this.workspace.role();

  constructor() {
    effect(() => {
      const revision = this.workspace.realtimeRevision();
      if (revision > 0) untracked(() => { if (this.studyId()) this.loadAnalysis(); });
    });
    effect(() => {
      const role = this.workspace.role();
      if (role === this.previousRole) return;
      this.previousRole = role;
      untracked(() => {
        if (this.studyId()) {
          this.clearRequests(); this.analysis.set(null); this.overview.set(null); this.selectedSeriesId.set(''); this.mode.set('study'); this.canStartAnalysis.set(false);
          this.preferencesReady.set(false); this.history.set([]); this.feedback.set([]);
          this.loadContext(); this.loadAnalysis();
        }
      });
    });
  }

  ngOnInit(): void {
    this.subscriptions.add(this.route.paramMap.subscribe(params => {
      const id = params.get('studyId') ?? '';
      if (!id || this.studyId() === id) return;
      this.clearRequests(); this.studyId.set(id); this.details.set(null); this.overview.set(null); this.overviewFallback.set('');
      this.selectedSeriesId.set(''); this.analysis.set(null); this.annotations.set([]); this.guideStep.set(0);
      this.mode.set(this.route.snapshot.queryParamMap.get('view') === 'report' ? 'report' : 'study');
      this.hiddenSeries.set([]); this.preferencesReady.set(false); this.loadContext(); this.loadAnalysis();
    }));
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe(); this.clearRequests();
  }

  protected hideSeries(id: string): void {
    this.hiddenSeries.update(items => [...new Set([...items, id])]);
    if (id === this.selectedSeriesId()) { this.selectedSeriesId.set(''); this.setMode('study'); }
    this.savePreferences();
  }
  protected hideTechnical(): void {
    const ids = (this.details()?.series ?? []).filter(isTechnicalSeries).map(s => s.id);
    this.hiddenSeries.update(items => [...new Set([...items, ...ids])]);
    if (ids.includes(this.selectedSeriesId())) { this.selectedSeriesId.set(''); this.mode.set('study'); }
    this.savePreferences();
  }
  protected restoreSeries(): void {
    this.hiddenSeries.set([]);
    this.guideStep.set(1);
    this.openSheet();
    this.savePreferences();
  }
  private savePreferences(): void {
    if (!this.preferencesReady()) return;
    const uids = (this.details()?.series ?? []).filter(s => this.hiddenSeries().includes(s.id)).map(s => s.seriesInstanceUid);
    this.contextSubscriptions.add(this.api.saveImagingPreferences(this.workspace.role(), this.studyId(), uids, this.mode()).subscribe({
      error: () => this.workspace.notify('Nie udało się zapisać preferencji. Bieżący widok nadal działa.'),
    }));
  }
  protected compareWith(id: string): void {
    this.comparisonSubscription?.unsubscribe();
    if (this.comparisonUrl()) URL.revokeObjectURL(this.comparisonUrl());
    this.comparisonUrl.set(''); this.comparisonError.set(''); this.comparisonId.set(id);
    this.comparisonLoading.set(false);
    if (!id || !this.clinician()) return;
    this.setMode('slice'); this.comparisonLoading.set(true);
    const requests = new Subscription(); this.comparisonSubscription = requests;
    requests.add(this.api.imagingOverview(this.workspace.role(), id).pipe(timeout(180_000)).subscribe({
      next: overview => {
        const series = overview.series.find(s => s.id === overview.selectedSeriesId);
        const frame = series?.frames[Math.floor(series.frames.length / 2)];
        if (!frame) { this.comparisonError.set('Brak przekroju do porównania.'); this.comparisonLoading.set(false); return; }
        requests.add(this.api.imagingPreviewImage(this.workspace.role(), id, frame.imageUrl).pipe(timeout(120_000)).subscribe({
          next: blob => { this.comparisonUrl.set(URL.createObjectURL(blob)); this.comparisonLoading.set(false); },
          error: () => { this.comparisonError.set('Nie udało się pobrać obrazu porównawczego.'); this.comparisonLoading.set(false); },
        }));
      },
      error: () => { this.comparisonError.set('Nie można otworzyć poprzedniego badania. Sprawdź zakres udostępnienia.'); this.comparisonLoading.set(false); },
    }));
  }
  protected selectAnalysis(id: string): void { this.mode.set('report'); this.feedback.set([]); this.loadAnalysis(id); }
  protected decide(findingId: string, action: string): void {
    const current = this.analysis(); if (!current || !this.clinician()) return;
    this.contextSubscriptions.add(this.api.saveImagingFeedback(this.workspace.role(), this.studyId(), current.id, findingId, action).subscribe({
      next: () => { this.feedback.update(items => [{findingId, action}, ...items]); this.workspace.notify('Decyzja lekarza została zapisana.'); },
      error: () => this.analysisError.set('Nie udało się zapisać decyzji.'),
    }));
  }
  protected decision(findingId: string): string {
    return ({CONFIRMED: 'Lekarz potwierdził', REJECTED: 'Lekarz odrzucił', NEEDS_REVIEW: 'Wymaga dalszej oceny', CORRECTED: 'Lekarz skorygował'} as Record<string, string>)[this.feedback().find(f => f.findingId === findingId)?.action ?? ''] ?? 'Model wskazał — wymaga oceny';
  }
  protected toggleScope(uid: string): void {
    this.scopeSelection.update(items => items.includes(uid) ? items.filter(value => value !== uid) : [...items, uid]);
  }
  protected back(): void {
    if (this.workspace.role() === 'patient') {
      this.workspace.selectView(this.workspace.studyReturnView());
      void this.router.navigateByUrl('/');
    } else void this.router.navigate(['/imaging-studies', this.studyId()]);
  }
  protected patientBackLabel(): string { return this.workspace.studyReturnView() === 'overview' ? 'Wstecz na Start' : 'Wstecz do badań'; }
  protected patientTitle(): string { const record = this.linkedRecord(); return record ? patientStudyTitle(record) : this.details()?.study.description || this.modalityLabel(); }
  protected downloadOriginal(): void {
    const record = this.linkedRecord();
    if (record?.primaryAssetId) this.workspace.download(record.primaryAssetId, record.originalFilename || 'oryginalne-obrazy');
  }
  protected setMode(mode: ViewMode): void {
    if (this.workspace.role() === 'patient') { this.mode.set(mode === 'report' || mode === 'advanced' ? mode : 'study'); return; }
    if (mode !== 'report' && this.history().length && this.analysis()?.id !== this.history()[0].id) this.loadAnalysis();
    this.mode.set(mode); this.zoom.set(100);
    if (mode === 'sheet' || mode === 'slice') {
      if (!this.currentSeries()?.frames.length) {
        const first = this.details()?.series.find(s => !this.hiddenSeries().includes(s.id));
        if (first) this.loadOverview(this.selectedSeriesId() || first.id); else this.mode.set('study');
      } else this.loadImages();
    }
  }
  /** Opens the first visible diagnostic series when the study overview has no active series yet. */
  protected openSheet(): void {
    const visible = this.details()?.series.filter(series => !this.hiddenSeries().includes(series.id) && series.imageCount > 0) ?? [];
    const first = visible.find(series => series.id === this.selectedSeriesId())
      ?? visible.find(series => !isTechnicalSeries(series)) ?? visible[0];
    if (!first) { this.workspace.notify('To badanie nie zawiera dostępnych przekrojów.'); return; }
    this.chooseSeries(first.id);
  }
  protected chooseSeries(id: string): void {
    if (this.history().length && this.analysis()?.id !== this.history()[0].id) this.loadAnalysis();
    this.mode.set('sheet');
    if (id === this.selectedSeriesId() && this.currentSeries()?.frames.length && !this.overviewFallback()) { this.loadImages(); return; }
    this.selectedSeriesId.set(id); this.loadOverview(id);
  }
  protected loadOverview(seriesId?: string): void {
    this.overviewSubscription?.unsubscribe(); this.imagesSubscription?.unsubscribe(); this.imageGeneration++;
    this.loading.set(true); this.error.set(''); this.overviewFallback.set(''); this.baseUrl.set(''); this.maskUrl.set('');
    this.overviewSubscription = this.api.imagingOverview(this.workspace.role(), this.studyId(), seriesId).pipe(timeout(180_000)).subscribe({
      next: value => {
        this.overviewFallback.set('');
        this.overview.set(value); this.selectedSeriesId.set(value.selectedSeriesId);
        this.frameIndex.set(Math.floor((this.currentSeries()?.frames.length ?? 0) / 2));
        this.loading.set(false); this.zoom.set(100);
        if (this.mode() === 'sheet' || this.mode() === 'slice') this.loadImages();
      },
      error: err => {
        this.loading.set(false);
        if ([404, 422, 502, 503].includes(Number(err?.status))) {
          this.error.set('');
          this.overviewFallback.set('Plansza jest chwilowo niedostępna. Możesz oglądać oryginalne obrazy pojedynczo i zmieniać przekrój suwakiem.');
          this.mode.set(this.workspace.role() === 'patient' ? 'slice' : 'advanced');
          return;
        }
        this.error.set('Nie udało się przygotować planszy obrazów. Spróbuj ponownie lub otwórz tryb specjalisty.');
      },
    });
  }
  protected changeFrame(value: number): void {
    const max = (this.currentSeries()?.frames.length ?? 1) - 1;
    this.frameIndex.set(Math.max(0, Math.min(max, Number(value)))); this.mode.set('slice'); this.loadImages();
  }
  protected pickFrame(event: MouseEvent): void {
    const series = this.currentSeries();
    if (!series || this.mode() !== 'sheet') return;
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const column = Math.floor((event.clientX - rect.left) / rect.width * series.contactSheetColumns);
    const row = Math.floor((event.clientY - rect.top) / rect.height * series.contactSheetRows);
    const index = row * series.contactSheetColumns + column;
    if (index < series.frames.length) this.changeFrame(index);
  }
  protected changeZoom(amount: number): void { this.zoom.set(Math.max(100, Math.min(300, this.zoom() + amount))); }
  protected fullscreen(): void {
    this.imageStage?.nativeElement.requestFullscreen()?.catch(() => this.workspace.notify('Ta przeglądarka nie udostępnia pełnego ekranu.'));
  }
  protected printReport(): void { window.print(); }
  protected sourceDocument(): void {
    const record = this.linkedRecord();
    if (record?.primaryAssetId) void this.router.navigate(['/documents', record.id]);
  }
  protected date(value?: string | null): string {
    return value && Number.isFinite(Date.parse(value)) ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' }).format(new Date(value)) : 'Data niepodana';
  }
  protected number(value?: number | null): string {
    return value == null ? '—' : new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2 }).format(value);
  }
  protected region(value: string): string {
    const labels: Record<string, string> = { brain: 'Mózg', skull: 'Czaszka', cerebral_bleed: 'Obszar wskazany przez model', brain_left: 'Mózg — lewa strona', brain_right: 'Mózg — prawa strona', white_matter: 'Istota biała', gray_matter: 'Istota szara', ventricle: 'Komory mózgu' };
    return labels[value] ?? value.replaceAll('_', ' ');
  }
  protected analysisStatus(value: ImagingAnalysis): string {
    return { QUEUED: 'W kolejce', RUNNING: 'Analiza w toku', SUCCEEDED: 'Analiza zakończona', DEGRADED: 'Analiza zakończona częściowo', UNSUPPORTED: 'Brak obsługi tej analizy', FAILED: 'Analiza nie powiodła się', CANCELLED: 'Analiza anulowana' }[value.status];
  }
  protected qualityLabel(value: ImagingAnalysis): string {
    return { acceptable: 'Odpowiednia do analizy', requires_review: 'Do sprawdzenia', not_assessed: 'Nie oceniono' }[value.quality.status ?? 'not_assessed'];
  }
  protected startAnalysis(selected = false): void {
    if (!this.clinician() || !this.canStartAnalysis()) return;
    this.analysisStarting.set(true); this.analysisError.set('');
    this.contextSubscriptions.add(this.api.startImagingAnalysis(this.workspace.role(), this.studyId(), selected ? this.scopeSelection() : undefined).subscribe({
      next: created => { this.analysisStarting.set(false); this.loadAnalysis(created.id); },
      error: err => { this.analysisStarting.set(false); this.analysisError.set(err?.error?.message ?? 'Nie udało się uruchomić analizy.'); },
    }));
  }
  protected downloadArtifact(artifact: ImagingAnalysisArtifact): void {
    const analysis = this.analysis(); if (!analysis) return;
    this.contextSubscriptions.add(this.api.downloadImagingAnalysisArtifact(this.workspace.role(), this.studyId(), analysis.id, artifact).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
        anchor.href = url; anchor.download = artifact.filename; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: () => this.analysisError.set('Nie udało się pobrać pliku segmentacji.'),
    }));
  }
  protected saveNote(): void {
    const text = this.note().trim(); if (!text) return;
    this.savingNote.set(true); this.annotationError.set(''); const frame = this.currentFrame();
    const body: SaveImagingAnnotation = {
      seriesId: this.selectedSeriesId() || null,
      sopInstanceUid: this.mode() === 'slice' ? frame?.sopInstanceUid ?? null : null,
      frameOfReferenceUid: null, type: 'NOTE',
      geometry: { kind: 'study-note', ...(this.mode() === 'slice' ? { frameIndex: this.frameIndex(), frameNumber: frame?.frameNumber } : {}) },
      measurement: { text },
    };
    this.contextSubscriptions.add(this.api.createImagingAnnotation(this.workspace.role(), this.studyId(), body).subscribe({
      next: created => { this.annotations.update(items => [...items, created]); this.note.set(''); this.savingNote.set(false); this.workspace.notify('Notatka do badania została zapisana.'); },
      error: err => { this.savingNote.set(false); this.annotationError.set(err?.error?.message ?? 'Nie udało się zapisać notatki.'); },
    }));
  }
  protected annotationText(annotation: ImagingAnnotation): string {
    const text = annotation.measurement['text']; return typeof text === 'string' ? text : 'Pomiar zapisany w trybie specjalisty';
  }
  protected loadImages(): void {
    this.imagesSubscription?.unsubscribe(); const generation = ++this.imageGeneration;
    const series = this.currentSeries(); const frame = this.currentFrame();
    const imagePath = this.mode() === 'sheet' ? series?.contactSheetUrl : frame?.imageUrl;
    const maskPath = this.mode() === 'sheet' ? series?.overlayContactSheetUrl : frame?.overlayUrl;
    this.baseUrl.set(''); this.maskUrl.set(''); this.imageError.set(''); this.overlayError.set('');
    if (!imagePath) { this.imageLoading.set(false); return; }
    this.imageLoading.set(true);
    const image = (path: string): Observable<Blob | string> => this.images.has(path)
      ? of(this.images.get(path)!)
      : this.api.imagingPreviewImage(this.workspace.role(), this.studyId(), path).pipe(timeout(120_000));
    this.imagesSubscription = forkJoin({
      base: image(imagePath),
      mask: maskPath ? image(maskPath).pipe(catchError(() => { this.overlayError.set('Nie udało się wczytać nakładki. Obraz oryginalny jest dostępny.'); return of(null); })) : of(null),
    }).subscribe({
      next: result => {
        if (generation !== this.imageGeneration) return;
        const keep = (path: string, value: Blob | string) => {
          if (typeof value === 'string') return value;
          const url = URL.createObjectURL(value); this.images.set(path, url);
          if (this.images.size > 18) {
            const oldest = this.images.keys().next().value;
            if (oldest && oldest !== imagePath && oldest !== maskPath) { URL.revokeObjectURL(this.images.get(oldest)!); this.images.delete(oldest); }
          }
          return url;
        };
        this.baseUrl.set(keep(imagePath, result.base));
        if (maskPath && result.mask) this.maskUrl.set(keep(maskPath, result.mask));
        this.imageLoading.set(false);
      },
      error: () => { this.imageLoading.set(false); this.imageError.set('Obraz nie został wczytany. Sprawdź połączenie i spróbuj ponownie.'); },
    });
  }
  private loadContext(): void {
    this.contextSubscriptions.add(this.api.imagingCapabilities(this.workspace.role(), this.studyId()).subscribe({
      next: caps => this.canStartAnalysis.set(caps.canStartAnalysis), error: () => this.canStartAnalysis.set(false),
    }));
    this.contextSubscriptions.add(this.api.imagingHistory(this.workspace.role(), this.studyId()).subscribe({
      next: items => this.history.set(items), error: () => this.history.set([]),
    }));
    this.contextSubscriptions.add(this.api.imagingStudy(this.workspace.role(), this.studyId()).subscribe({
      next: details => {
        this.details.set(details); this.loading.set(false);
        if (this.clinician()) this.contextSubscriptions.add(this.api.imagingStudies(this.workspace.role(), details.study.patientId).subscribe({
          next: items => this.priorStudies.set(items.filter(s => s.id !== this.studyId() && s.status === 'AVAILABLE'
            && s.modality === details.study.modality && !!s.performedAt && !!details.study.performedAt
            && Date.parse(s.performedAt) < Date.parse(details.study.performedAt))),
          error: () => this.priorStudies.set([]),
        }));
        this.hiddenSeries.set(details.series.filter(isTechnicalSeries).map(s => s.id));
        this.scopeSelection.set(details.series.filter(s => !isTechnicalSeries(s)).map(s => s.seriesInstanceUid));
        this.contextSubscriptions.add(this.api.imagingPreferences(this.workspace.role(), this.studyId()).subscribe({
          next: prefs => {
            if (prefs.hiddenSeriesUids !== null) this.hiddenSeries.set(details.series.filter(s => prefs.hiddenSeriesUids?.includes(s.seriesInstanceUid)).map(s => s.id));
            this.preferencesReady.set(true);
          }, error: () => this.preferencesReady.set(true),
        }));
      },
      error: () => { this.loading.set(false); this.error.set('Nie można pobrać danych badania. Sprawdź dostęp i spróbuj ponownie.'); },
    }));
    this.contextSubscriptions.add(this.api.imagingAnnotations(this.workspace.role(), this.studyId()).subscribe({
      next: items => this.annotations.set(items), error: () => this.annotationError.set('Nie udało się pobrać notatek.'),
    }));
  }
  private loadAnalysis(analysisId?: string): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
    const request = analysisId ? this.api.imagingAnalysis(this.workspace.role(), this.studyId(), analysisId) : this.api.latestImagingAnalysis(this.workspace.role(), this.studyId());
    this.contextSubscriptions.add(request.subscribe({
      next: value => {
        const wasRunning = this.analysisRunning(); this.analysis.set(value);
        if (value && this.clinician()) this.contextSubscriptions.add(this.api.imagingFeedback(this.workspace.role(), this.studyId(), value.id).subscribe({
          next: items => this.feedback.set(items), error: () => this.feedback.set([]),
        }));
        if (this.analysisRunning()) this.pollTimer = setTimeout(() => this.loadAnalysis(value!.id), 3000);
        else if (wasRunning && value && ['SUCCEEDED', 'DEGRADED'].includes(value.status)) {
          this.images.forEach(url => URL.revokeObjectURL(url)); this.images.clear(); if (this.mode() !== 'study') this.loadOverview(this.selectedSeriesId() || undefined);
        }
      },
      error: () => this.analysisError.set('Nie udało się pobrać wyniku analizy. Obrazy nadal możesz przeglądać.'),
    }));
  }
  private clearRequests(): void {
    this.comparisonSubscription?.unsubscribe();
    if (this.comparisonUrl()) URL.revokeObjectURL(this.comparisonUrl());
    this.comparisonUrl.set(''); this.comparisonId.set(''); this.priorStudies.set([]);
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.contextSubscriptions.unsubscribe(); this.contextSubscriptions = new Subscription();
    this.overviewSubscription?.unsubscribe(); this.imagesSubscription?.unsubscribe();
    this.images.forEach(url => URL.revokeObjectURL(url)); this.images.clear();
    this.baseUrl.set(''); this.maskUrl.set(''); this.imageGeneration++;
  }
}

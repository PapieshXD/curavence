import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ReadingGuide } from '../../../../shared/components/reading-guide/reading-guide';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize, Subscription } from 'rxjs';
import { ImagingStudyDetails } from '../../../../core/models/imaging';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';

@Component({
  selector: 'app-mri-study-details',
  imports: [AppIcon, StatusText, FormsModule, ReadingGuide],
  templateUrl: './mri-study-details.html',
  styleUrl: './mri-study-details.scss',
})
export class MriStudyDetailsPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly studyId = this.route.snapshot.paramMap.get('studyId') ?? '';
  protected readonly details = signal<ImagingStudyDetails | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly report = signal<{documentId: string | null; title: string | null} | null>(null);
  protected readonly reportLoading = signal(false);
  protected readonly reportError = signal('');
  protected readonly reportEnabled = signal(false);
  protected readonly linking = signal(false);
  protected selectedReport = '';
  protected readonly reportCandidates = computed(() => (this.workspace.data()?.studies ?? [])
    .filter(s => s.patientId === this.details()?.study.patientId && !s.imagingStudyId && s.id !== this.studyId));
  constructor() {
    effect(onCleanup => {
      const details = this.details(), role = this.workspace.role();
      if (!details) return;
      const subscriptions = new Subscription();
      this.report.set(null); this.reportLoading.set(true); this.reportError.set('');
      subscriptions.add(this.api.readingGuideConfig().subscribe({next: config => {
        this.reportEnabled.set(config.enabled);
        if (!config.enabled) { this.reportLoading.set(false); return; }
        subscriptions.add(this.api.reportDocument(role, this.studyId).pipe(finalize(() => this.reportLoading.set(false))).subscribe({
          next: report => { this.report.set(report); this.selectedReport = report.documentId ?? ''; },
          error: err => this.reportError.set(err?.error?.message ?? 'Nie można pobrać powiązanego opisu.'),
        }));
      }, error: () => { this.reportLoading.set(false); this.reportEnabled.set(false); }}));
      onCleanup(() => subscriptions.unsubscribe());
    });
  }
  protected linkReport(): void {
    if (this.linking()) return;
    this.linking.set(true); this.reportError.set('');
    this.api.linkReportDocument(this.workspace.role(), this.studyId, this.selectedReport || null)
      .pipe(finalize(() => this.linking.set(false))).subscribe({
        next: () => this.report.set({documentId: this.selectedReport || null,
          title: this.reportCandidates().find(s => s.id === this.selectedReport)?.title ?? null}),
        error: err => this.reportError.set(err?.error?.message ?? 'Nie udało się powiązać opisu.'),
      });
  }
  protected openReport(): void {
    const id = this.report()?.documentId;
    const document = this.reportCandidates().find(s => s.id === id);
    if (document?.primaryAssetId) this.workspace.download(document.primaryAssetId, document.originalFilename ?? 'opis-badania');
    else if (id) void this.router.navigate(['/documents', id]);
  }

  ngOnInit(): void { this.load(); }

  protected load(): void {
    if (!this.studyId) return;
    this.loading.set(true);
    this.error.set('');
    this.api.imagingStudy(this.workspace.role(), this.studyId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (details) => this.details.set(details),
        error: (error) => this.error.set(error?.error?.message ?? 'Nie udało się pobrać badania obrazowego.'),
      });
  }

  protected open(): void {
    void this.router.navigate(['/imaging-studies', this.studyId, 'viewer']);
  }

  protected back(): void {
    if (this.workspace.role() === 'patient') {
      this.workspace.selectView(this.workspace.studyReturnView());
      void this.router.navigateByUrl('/');
      return;
    }
    const patientId = this.details()?.study.patientId;
    if (patientId) void this.router.navigate(['/patients', patientId, 'imaging']);
    else void this.router.navigate(['/']);
  }

  protected patientBackLabel(): string { return this.workspace.studyReturnView() === 'overview' ? 'Wstecz na Start' : 'Wstecz do badań'; }

  protected date(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' }).format(new Date(value)) : 'Nie podano';
  }

  protected displayTitle(description: string, modality: string | null): string {
    return { MR: 'Rezonans magnetyczny', CT: 'Tomografia komputerowa', CR: 'Badanie RTG', DX: 'Badanie RTG', US: 'Badanie USG' }[modality ?? ''] ?? 'Badanie obrazowe';
  }
}

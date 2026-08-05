import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';
import { ImagingStudySummary } from '../../../../core/models/imaging';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';

@Component({
  selector: 'app-mri-study-list',
  imports: [AppIcon, StatusText],
  templateUrl: './mri-study-list.html',
  styleUrl: './mri-study-list.scss',
})
export class MriStudyList implements OnInit {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly studies = signal<ImagingStudySummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly patientId = this.route.snapshot.paramMap.get('patientId') ?? '';
  protected readonly canUpload = computed(
    () => this.workspace.data()?.profile?.patientId === this.patientId,
  );
  protected readonly visibleStudies = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return this.studies().filter((study) =>
      !query
        ? true
        : `${study.description} ${study.bodyPart ?? ''} ${study.performedAt ?? ''}`
            .toLocaleLowerCase('pl')
            .includes(query),
    );
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    if (!this.patientId) {
      this.loading.set(false);
      this.error.set('Nie wskazano pacjenta.');
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.api
      .imagingStudies(this.workspace.role(), this.patientId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (studies) => this.studies.set(studies),
        error: (error) =>
          this.error.set(error?.error?.message ?? 'Nie udało się pobrać listy badań obrazowych.'),
      });
  }

  protected upload(): void {
    void this.router.navigate(['/patients', this.patientId, 'imaging', 'upload']);
  }

  protected details(study: ImagingStudySummary): void {
    void this.router.navigate(['/imaging-studies', study.id]);
  }

  protected open(study: ImagingStudySummary): void {
    if (study.status !== 'AVAILABLE') return;
    void this.router.navigate(['/imaging-studies', study.id, 'viewer']);
  }

  protected retry(study: ImagingStudySummary): void {
    if (study.status !== 'FAILED' || !this.canUpload()) return;
    this.api.retryImagingStudy(this.workspace.role(), study.id).subscribe({
      next: () => this.load(),
      error: (error) => this.error.set(error?.error?.message ?? 'Nie udało się ponowić importu.'),
    });
  }

  protected date(value: string | null): string {
    return value
      ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value))
      : 'Brak daty';
  }

  protected statusLabel(status: ImagingStudySummary['status']): string {
    return {
      UPLOADING: 'Przesyłanie',
      VALIDATING: 'Walidacja',
      STORING: 'Zapisywanie',
      VERIFYING: 'Weryfikacja',
      AVAILABLE: 'Dostępne',
      FAILED: 'Błąd',
      CANCELLED: 'Anulowane',
    }[status];
  }

  protected statusTone(status: ImagingStudySummary['status']): 'success' | 'danger' | 'warning' {
    if (status === 'AVAILABLE') return 'success';
    if (status === 'FAILED') return 'danger';
    return 'warning';
  }
}

import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';
import { ImagingStudyDetails } from '../../../../core/models/imaging';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';

@Component({
  selector: 'app-mri-study-details',
  imports: [AppIcon, StatusText],
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
    const patientId = this.details()?.study.patientId;
    if (patientId) void this.router.navigate(['/patients', patientId, 'imaging']);
    else void this.router.navigate(['/']);
  }

  protected date(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' }).format(new Date(value)) : 'Nie podano';
  }

  protected displayTitle(description: string, modality: string | null): string {
    return { MR: 'Rezonans magnetyczny', CT: 'Tomografia komputerowa', CR: 'Badanie RTG', DX: 'Badanie RTG', US: 'Badanie USG' }[modality ?? ''] ?? 'Badanie obrazowe';
  }
}

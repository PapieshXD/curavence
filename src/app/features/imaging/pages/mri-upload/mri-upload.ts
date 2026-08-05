import { HttpEventType } from '@angular/common/http';
import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, timer, switchMap, takeWhile } from 'rxjs';
import { ImagingStatus } from '../../../../core/models/imaging';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';

@Component({
  selector: 'app-mri-upload',
  imports: [FormsModule, AppIcon],
  templateUrl: './mri-upload.html',
  styleUrl: './mri-upload.scss',
})
export class MriUpload implements OnDestroy {
  private readonly api = inject(Api);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private polling?: Subscription;

  protected readonly patientId = this.route.snapshot.paramMap.get('patientId') ?? '';
  protected readonly files = signal<File[]>([]);
  protected readonly description = signal('');
  protected readonly performedAt = signal('');
  protected readonly progress = signal(0);
  protected readonly status = signal<ImagingStatus | 'IDLE'>('IDLE');
  protected readonly error = signal('');
  protected readonly submitting = signal(false);
  protected readonly dragActive = signal(false);

  ngOnDestroy(): void {
    this.polling?.unsubscribe();
  }

  protected chooseFiles(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.setFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  protected drop(event: DragEvent): void {
    event.preventDefault();
    this.dragActive.set(false);
    this.setFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  protected dragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragActive.set(true);
  }

  protected remove(index: number): void {
    this.files.update((files) => files.filter((_, fileIndex) => fileIndex !== index));
  }

  protected back(): void {
    void this.router.navigate(['/patients', this.patientId, 'imaging']);
  }

  protected upload(): void {
    this.error.set('');
    if (!this.patientId || !this.description().trim() || this.files().length === 0) {
      this.error.set('Wybierz pliki i wpisz nazwę badania.');
      return;
    }
    if (!this.validSelection(this.files())) return;

    const form = new FormData();
    for (const file of this.files()) form.append('file', file, file.name);
    form.append('description', this.description().trim());
    if (this.performedAt()) {
      form.append('performedAt', new Date(`${this.performedAt()}T00:00:00`).toISOString());
    }

    this.submitting.set(true);
    this.status.set('UPLOADING');
    this.progress.set(1);
    this.api.uploadImagingStudy(this.workspace.role(), this.patientId, form).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress) {
          const total = event.total ?? event.loaded;
          this.progress.set(Math.min(50, Math.round((event.loaded / total) * 50)));
        }
        if (event.type === HttpEventType.Response && event.body) {
          this.progress.set(event.body.progress);
          this.status.set(event.body.status);
          if (event.body.status === 'AVAILABLE') this.finish();
          else this.poll(event.body.id);
        }
      },
      error: (error) => {
        this.submitting.set(false);
        this.status.set('FAILED');
        this.progress.set(100);
        this.error.set(error?.error?.message ?? 'Nie udało się zaimportować badania MRI.');
      },
    });
  }

  protected statusLabel(): string {
    return {
      IDLE: 'Gotowe do przesłania',
      UPLOADING: 'Przesyłanie plików',
      VALIDATING: 'Sprawdzanie i przygotowanie obrazów',
      STORING: 'Zapisywanie w magazynie DICOM',
      VERIFYING: 'Weryfikacja kompletności badania',
      AVAILABLE: 'Badanie jest dostępne',
      FAILED: 'Import zakończył się błędem',
      CANCELLED: 'Import został anulowany',
    }[this.status()];
  }

  protected fileSize(bytes: number): string {
    if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  private setFiles(files: File[]): void {
    this.error.set('');
    if (!this.validSelection(files)) return;
    this.files.set(files);
  }

  private validSelection(files: File[]): boolean {
    const lowerNames = files.map((file) => file.name.toLocaleLowerCase());
    const zipCount = lowerNames.filter((name) => name.endsWith('.zip')).length;
    const niftiCount = lowerNames.filter((name) => name.endsWith('.nii') || name.endsWith('.nii.gz')).length;
    const invalid = files.some((file, index) => {
      const name = lowerNames[index];
      const hasExtension = file.name.includes('.');
      return !name.endsWith('.zip') && !name.endsWith('.dcm') && !name.endsWith('.nii')
        && !name.endsWith('.nii.gz') && file.type !== 'application/dicom' && hasExtension;
    });
    if (invalid) {
      this.error.set('Dozwolone są pliki .nii, .nii.gz, pliki DICOM lub archiwum .zip.');
      return false;
    }
    if (zipCount > 0 && (zipCount !== 1 || files.length !== 1)) {
      this.error.set('Archiwum ZIP prześlij jako pojedynczy plik.');
      return false;
    }
    if (niftiCount > 0 && (niftiCount !== 1 || files.length !== 1)) {
      this.error.set('Plik NIfTI prześlij pojedynczo; nie łącz go z DICOM ani ZIP.');
      return false;
    }
    return true;
  }

  private poll(studyId: string): void {
    this.polling = timer(0, 1200)
      .pipe(
        switchMap(() => this.api.imagingStatus(this.workspace.role(), studyId)),
        takeWhile((result) => !['AVAILABLE', 'FAILED', 'CANCELLED'].includes(result.status), true),
      )
      .subscribe({
        next: (result) => {
          this.status.set(result.status);
          this.progress.set(result.progress);
          if (result.status === 'FAILED' || result.status === 'CANCELLED') {
            this.submitting.set(false);
            this.error.set(result.errorMessage ?? 'Import zakończył się błędem.');
          } else if (result.status === 'AVAILABLE') {
            this.finish();
          }
        },
        error: () => {
          this.submitting.set(false);
          this.error.set('Nie udało się odczytać statusu importu.');
        },
      });
  }

  private finish(): void {
    this.submitting.set(false);
    this.status.set('AVAILABLE');
    this.progress.set(100);
    window.setTimeout(() => this.back(), 900);
  }
}

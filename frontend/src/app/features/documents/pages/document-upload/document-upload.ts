import { HttpEventType } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { DocumentUploadResult } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { DocumentService } from '../../services/document';
import { BatchUpload } from '../../components/batch-upload';

const ECG_EXTENSIONS = ['pdf', 'png', 'jpg', 'jpeg', 'gif', 'bmp', 'tif', 'tiff', 'webp', 'txt', 'csv', 'tsv', 'xml', 'json', 'ecg', 'scp', 'edf', 'bdf', 'gdf', 'hea', 'dat', 'atr', 'mat', 'dcm', 'dicom', 'zip'];

const MAX_PDF_BYTES = 25 * 1024 * 1024;

@Component({
  selector: 'app-document-upload',
  imports: [FormsModule, AppIcon, BatchUpload],
  templateUrl: './document-upload.html',
  styleUrl: './document-upload.scss',
})
export class DocumentUpload {
  private readonly documents = inject(DocumentService);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router, { optional: true });
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  protected readonly file = signal<File | null>(null);
  protected readonly kind = signal('document');
  protected readonly studyType = signal('MEDICAL_DOCUMENT');
  protected readonly studyTypes = [
    {code:'MEDICAL_DOCUMENT',name:'Dokument medyczny'},
    {code:'LABORATORY',name:'Badanie laboratoryjne'},
    {code:'ULTRASOUND',name:'USG — opis / wydruk'},
    {code:'EKG',name:'EKG — zapis / opis'},
    {code:'EEG',name:'EEG — zapis / opis'},
    {code:'MRI',name:'MRI — opis w PDF'},
    {code:'CT',name:'Tomografia komputerowa — opis w PDF'},
    {code:'XRAY',name:'RTG — opis w PDF'},
    {code:'OTHER',name:'Inny dokument'},
  ];
  protected readonly isEcg = computed(() => this.studyType() === 'EKG');
  protected readonly acceptedFiles = computed(() => this.isEcg() ? ECG_EXTENSIONS.map(ext => '.' + ext).join(',') : '.pdf,application/pdf');
  protected readonly title = signal('');
  protected readonly performedAt = signal('');
  protected readonly patientId = signal('');
  protected readonly progress = signal(0);
  protected readonly error = signal('');
  protected readonly submitting = signal(false);
  protected readonly dragActive = signal(false);
  protected readonly result = signal<DocumentUploadResult | null>(null);
  protected readonly canUpload = computed(
    () => this.workspace.data()?.session.superuser || ['patient', 'admin'].includes(this.workspace.role()),
  );
  protected readonly canChoosePatient = computed(
    () => this.workspace.role() === 'admin' || !!this.workspace.data()?.session.superuser,
  );

  constructor() {
    const requestedType = this.route?.snapshot.queryParamMap.get('type');
    if (requestedType && this.studyTypes.some((type)=>type.code === requestedType)) this.studyType.set(requestedType);
  }

  protected chooseKind(kind: string): void {
    if (this.submitting()) return;
    this.kind.set(kind);
    this.studyType.set(({laboratory:'LABORATORY',document:'MEDICAL_DOCUMENT',other:'OTHER',functional:'EKG',ultrasound:'ULTRASOUND'} as Record<string,string>)[kind] || 'MEDICAL_DOCUMENT');
  }

  protected openDuplicate(id:string):void {void this.router?.navigate(['/documents',id]);}
  protected keepBoth():void {this.result.update(result=>result?{...result,duplicateIds:[]}:null);this.workspace.notify('Zachowano oba pliki. Każdy oryginał pozostaje dostępny.');}
  protected openSaved(): void {
    const id = this.result()?.id;
    if (id) {
      if (this.workspace.role() === 'patient') this.workspace.studyReturnView.set('results');
      void this.router?.navigate(['/documents',id]);
    }
  }

  protected chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.setFile(input.files?.[0] ?? null);
    input.value = '';
  }

  protected drop(event: DragEvent): void {
    event.preventDefault();
    if (this.submitting()) return;
    this.dragActive.set(false);
    this.setFile(event.dataTransfer?.files?.[0] ?? null);
  }

  protected dragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragActive.set(true);
  }

  protected removeFile(): void {
    if (this.submitting()) return;
    this.file.set(null);
    this.result.set(null);
    this.progress.set(0);
  }

  protected startAnother(): void {
    this.file.set(null);
    this.title.set('');
    this.performedAt.set('');
    this.result.set(null);
    this.progress.set(0);
    this.error.set('');
  }

  protected back(): void {
    this.workspace.selectView(this.workspace.role() === 'patient' ? 'results' : 'documents');
    void this.router?.navigateByUrl('/');
  }

  protected openImagingUpload(): void {
    const patientId = this.workspace.data()?.profile?.patientId;
    if (!patientId) { this.workspace.notify('Nie udało się odczytać profilu pacjenta.'); return; }
    void this.router?.navigate(['/patients', patientId, 'imaging', 'upload']);
  }

  protected submit(): void {
    this.error.set('');
    this.result.set(null);
    const file = this.file();
    if (!this.canUpload()) {
      this.error.set('W tym widoku nie możesz dodawać dokumentów.');
      return;
    }
    if (!file || !this.title().trim()) {
      this.error.set('Wybierz plik i wpisz nazwę badania lub dokumentu.');
      return;
    }
    if (!this.validateFile(file)) return;

    this.submitting.set(true);
    this.progress.set(1);
    this.documents
      .upload(
        this.workspace.role(),
        file,
        this.title(),
        this.patientId() || undefined,
        this.performedAt() || undefined,
        this.studyType(),
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress) {
            const total = event.total ?? event.loaded;
            this.progress.set(Math.min(85, Math.max(1, Math.round((event.loaded / total) * 85))));
          }
          if (event.type === HttpEventType.Response && event.body) {
            this.result.set(event.body);
            this.progress.set(100);
            this.submitting.set(false);
            this.workspace.load();
            this.workspace.notify('Dane zostały zapisane i uporządkowane.');
          }
        },
        error: (error) => {
          this.submitting.set(false);
          this.progress.set(0);
          this.error.set(error?.error?.message ?? 'Nie udało się dodać danych medycznych.');
        },
      });
  }

  protected fileSize(bytes: number): string {
    if (bytes < 1024 * 1024) return `${Math.max(1, Math.ceil(bytes / 1024))} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  private setFile(file: File | null): void {
    this.error.set('');
    this.result.set(null);
    this.progress.set(0);
    if (!file || !this.validateFile(file)) {
      this.file.set(null);
      return;
    }
    this.file.set(file);
    if (!this.title().trim()) {
      this.title.set(file.name.replace(/\.[^.]+$/, ''));
    }
  }

  private validateFile(file: File): boolean {
    const isPdf = file.type === 'application/pdf' || file.name.toLocaleLowerCase().endsWith('.pdf');
    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    if (this.isEcg() ? !ECG_EXTENSIONS.includes(extension) : !isPdf) {
      this.error.set(this.isEcg() ? 'Nieobsługiwany format EKG. Wybierz jeden z wymienionych formatów.' : 'Dozwolone są wyłącznie dokumenty PDF.');
      return false;
    }
    if (file.size > MAX_PDF_BYTES) {
      this.error.set('Plik może mieć maksymalnie 25 MB.');
      return false;
    }
    if (file.size === 0) {
      this.error.set('Wybrany dokument jest pusty.');
      return false;
    }
    return true;
  }
}

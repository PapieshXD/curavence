import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-document-list', imports: [FormsModule, AppIcon, StatusText, EnumLabelPipe], templateUrl: './document-list.html', styleUrl: './document-list.scss' })
export class DocumentList {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly category = signal('all');
  protected readonly selectedId = signal('');
  protected readonly uploadOpen = signal(false);
  protected readonly title = signal('');
  protected readonly typeCode = signal('MEDICAL_DOCUMENT');
  protected readonly patientId = signal('');
  protected readonly file = signal<File | null>(null);
  protected readonly uploading = signal(false);
  protected readonly canUpload = computed(() => this.workspace.data()?.session.superuser || ['patient', 'admin'].includes(this.workspace.role()));
  protected readonly documents = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.studies ?? []).filter((study) =>
      (this.category() === 'all' || study.typeCode === this.category()) &&
      (!query || `${study.title} ${study.patientName} ${study.typeName} ${study.sourceName}`.toLocaleLowerCase('pl').includes(query)));
  });
  protected readonly selected = computed(() => {
    const items = this.documents();
    return items.find((item) => item.id === this.selectedId()) ?? items[0] ?? null;
  });

  protected chooseFile(event: Event): void { this.file.set((event.target as HTMLInputElement).files?.[0] ?? null); }
  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Brak daty'; }
  protected size(value: number | null): string { if (value == null) return 'Brak pliku'; return value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`; }
  protected statusTone(value: string): 'success' | 'danger' | 'warning' | 'neutral' { return value === 'READY' ? 'success' : value === 'FAILED' ? 'danger' : value === 'UPLOADING' ? 'warning' : 'neutral'; }
  protected download(assetId: string | null, filename: string | null): void { assetId ? this.workspace.download(assetId, filename ?? 'dokument') : this.workspace.notify('Badanie nie ma jeszcze pliku.'); }

  protected upload(): void {
    const file = this.file();
    if (!file || !this.title().trim()) { this.workspace.notify('Wybierz plik i wpisz nazwę badania.'); return; }
    this.uploading.set(true);
    this.workspace.uploadStudy(file, this.title(), this.typeCode(), this.patientId() || undefined).subscribe({
      next: () => { this.uploading.set(false); this.uploadOpen.set(false); this.title.set(''); this.file.set(null); this.workspace.notify('Plik zapisano w MinIO, a metadane w PostgreSQL.'); },
      error: (error) => { this.uploading.set(false); this.workspace.notify(error?.error?.message ?? 'Nie udało się przesłać pliku.'); },
    });
  }
}

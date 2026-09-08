import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Subscription, finalize } from 'rxjs';
import { DocumentConversationMessage } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';
import { DocumentService } from '../../services/document';

@Component({
  selector: 'app-document-details',
  imports: [FormsModule, AppIcon, StatusText, EnumLabelPipe],
  templateUrl: './document-details.html',
  styleUrl: './document-details.scss',
})
export class DocumentDetails {
  readonly documentId = input('');

  protected readonly workspace = inject(WorkspaceStore);
  private readonly documents = inject(DocumentService);
  private readonly router = inject(Router, { optional: true });
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly document = computed(() =>
    this.workspace.data()?.studies.find((item) => item.id === this.documentId()) ?? null,
  );
  protected readonly canManage = computed(() =>
    Boolean(this.workspace.data()?.session.superuser)
      || ['patient', 'admin'].includes(this.workspace.role()),
  );
  protected readonly tab = signal<'info' | 'chat'>('info');
  protected readonly title = signal('');
  protected readonly performedAt = signal('');
  protected readonly saving = signal(false);
  protected readonly archiveConfirm = signal(false);
  protected readonly archiving = signal(false);

  protected readonly previewUrl = signal<SafeResourceUrl | null>(null);
  protected readonly imagePreviewUrl = signal<string | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly previewError = signal('');

  protected readonly question = signal('');
  protected readonly asking = signal(false);
  protected readonly chatError = signal('');
  protected readonly messages = signal<DocumentConversationMessage[]>([]);
  protected readonly suggestions = [
    'Podsumuj najważniejsze informacje',
    'Jakie daty występują w dokumencie?',
    'Jakie wyniki lub wartości podano?',
  ];

  private loadedAssetId = '';
  private formSignature = '';
  private objectUrl: string | null = null;
  private previewSubscription?: Subscription;

  constructor() {
    effect(() => {
      const current = this.document();
      if (!current) return;
      const signature = `${current.id}|${current.title}|${current.performedAt ?? ''}`;
      if (signature !== this.formSignature) {
        this.formSignature = signature;
        this.title.set(current.title);
        this.performedAt.set(this.dateInputValue(current.performedAt));
      }
      if (current.primaryAssetId && current.primaryAssetId !== this.loadedAssetId) {
        this.loadPreview(current.primaryAssetId);
      }
    });
    this.destroyRef.onDestroy(() => this.clearPreview());
  }

  protected date(value: string | null): string {
    return value
      ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' }).format(new Date(value))
      : 'Brak daty';
  }

  protected displayTitle(title: string): string {
    return title
      .replace(/^\d+[_\s-]*/, '')
      .replace(/_/g, ' ')
      .replace(/^./, (letter) => letter.toLocaleUpperCase('pl'));
  }

  protected size(value: number | null): string {
    if (value == null) return 'Brak danych';
    return value < 1024 * 1024
      ? `${Math.ceil(value / 1024)} KB`
      : `${(value / 1024 / 1024).toFixed(1)} MB`;
  }

  private dateInputValue(value: string | null): string {
    if (!value) return '';
    const date = new Date(value);
    const year = date.getFullYear();
    const month = `${date.getMonth() + 1}`.padStart(2, '0');
    const day = `${date.getDate()}`.padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  protected isPdf(): boolean {
    return this.document()?.mimeType === 'application/pdf';
  }

  protected isImage(): boolean {
    return this.document()?.mimeType?.startsWith('image/') ?? false;
  }

  protected goBack(): void {
    this.workspace.selectView('documents');
    void this.router?.navigateByUrl('/');
  }

  protected download(): void {
    const current = this.document();
    if (current?.primaryAssetId) {
      this.workspace.download(current.primaryAssetId, current.originalFilename ?? 'dokument');
    }
  }

  protected save(): void {
    const current = this.document();
    const cleanTitle = this.title().trim();
    if (!current || !cleanTitle || this.saving()) return;
    this.saving.set(true);
    this.documents.update(
      this.workspace.role(), current.id, cleanTitle, this.performedAt(),
    ).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.workspace.notify('Zapisano dane dokumentu.');
        this.workspace.load();
      },
      error: (error) => this.workspace.notify(
        error?.error?.message ?? 'Nie udało się zapisać zmian.',
      ),
    });
  }

  protected archive(): void {
    const current = this.document();
    if (!current || this.archiving()) return;
    if (!this.archiveConfirm()) {
      this.archiveConfirm.set(true);
      return;
    }
    this.archiving.set(true);
    this.documents.archive(this.workspace.role(), current.id)
      .pipe(finalize(() => this.archiving.set(false)))
      .subscribe({
        next: () => {
          this.workspace.notify('Dokument przeniesiono do archiwum.');
          this.workspace.selectView('documents');
          this.workspace.load();
          void this.router?.navigateByUrl('/');
        },
        error: (error) => this.workspace.notify(
          error?.error?.message ?? 'Nie udało się zarchiwizować dokumentu.',
        ),
      });
  }

  protected askSuggestion(suggestion: string): void {
    this.question.set(suggestion);
    this.ask();
  }

  protected questionKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.ask();
    }
  }

  protected ask(): void {
    const current = this.document();
    const content = this.question().trim();
    if (!current || !current.hasExtractedText || !content || this.asking()) return;
    const history = this.messages().slice(-6);
    this.messages.update((items) => [...items, { role: 'user', content }]);
    this.question.set('');
    this.chatError.set('');
    this.asking.set(true);
    this.documents.ask(this.workspace.role(), current.id, content, history)
      .pipe(finalize(() => this.asking.set(false)))
      .subscribe({
        next: (response) => {
          this.messages.update((items) => [
            ...items,
            { role: 'assistant', content: response.answer },
          ]);
          if (response.contextTruncated) {
            this.workspace.notify('Odpowiedź użyła skróconej treści bardzo długiego dokumentu.');
          }
        },
        error: (error) => this.chatError.set(
          error?.error?.message ?? 'Model nie odpowiedział. Sprawdź, czy Ollama jest uruchomiona.',
        ),
      });
  }

  private loadPreview(assetId: string): void {
    this.previewSubscription?.unsubscribe();
    this.clearObjectUrl();
    this.loadedAssetId = assetId;
    this.previewLoading.set(true);
    this.previewError.set('');
    this.previewSubscription = this.documents.preview(this.workspace.role(), assetId)
      .pipe(finalize(() => this.previewLoading.set(false)))
      .subscribe({
        next: (blob) => {
          this.objectUrl = URL.createObjectURL(blob);
          this.imagePreviewUrl.set(this.objectUrl);
          this.previewUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.objectUrl));
        },
        error: () => this.previewError.set('Nie udało się wczytać podglądu pliku.'),
      });
  }

  private clearPreview(): void {
    this.previewSubscription?.unsubscribe();
    this.clearObjectUrl();
  }

  private clearObjectUrl(): void {
    if (this.objectUrl) URL.revokeObjectURL(this.objectUrl);
    this.objectUrl = null;
    this.previewUrl.set(null);
    this.imagePreviewUrl.set(null);
  }
}

import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MetadataHistory } from '../../../../core/models/readiness';
import { ReadingGuide } from '../../../../shared/components/reading-guide/reading-guide';
import { EcgPreview, EcgCalibration, EcgSelection } from '../../../../core/models/ecg';
import { Api } from '../../../../core/services/api';
import { EcgWaveform } from '../../components/ecg-waveform';
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
import { patientStudyStatus, patientStudyTitle } from '../../../patient/patient-study';

@Component({
  selector: 'app-document-details',
  imports: [DatePipe, ReadingGuide, FormsModule, AppIcon, StatusText, EnumLabelPipe, EcgWaveform],
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
  protected readonly readData = computed(() => {
    const result = this.document()?.resultSet;
    return result ? [...result.parameters.map(p => `${p.name}: ${p.value ?? 'Brak wartości'} ${p.unit ?? ''}`),
      ...(result.sections ?? []).map(s => `${s.title}\n${s.content}`), result.comment ?? ''].filter(Boolean).join('\n\n') : '';
  });
  protected readonly canManage = computed(() =>
    Boolean(this.workspace.data()?.session.superuser)
      || ['patient', 'admin'].includes(this.workspace.role()),
  );
  private readonly api = inject(Api);
  protected readonly ecg = signal<EcgPreview | null>(null);
  protected readonly ecgLoading = signal(false);
  protected readonly ecgError = signal('');
  protected readonly ecgCalibration = signal<EcgCalibration>({ samplingRate:null, unit:null });
  protected readonly isSignal = computed(() => ['EKG', 'ECG'].includes(this.document()?.typeCode ?? '') && !this.isPdf());
  protected readonly canAsk = computed(() => !!this.document()?.hasExtractedText || !!this.ecg());
  protected readonly aiModel = signal('');
  protected readonly aiAudience = signal<'patient'|'clinician'>('patient');
  protected readonly ecgSelection = signal<EcgSelection>({channelIndex:0,startSample:0,sampleCount:0});
  private ecgSubscription?: Subscription;
  private questionSubscription?: Subscription;
  private loadedDocumentId = '';
  protected readonly tab = signal<'info' | 'chat'>('info');
  protected readonly metadataDirty=signal(false);
  protected readonly metadataHistory=signal<MetadataHistory[]>([]);
  protected readonly metadataError=signal('');
  protected readonly categoryCode=signal('');
  protected readonly editableCategories=[{code:'MEDICAL_DOCUMENT',label:'Dokument medyczny'},{code:'LABORATORY',label:'Badanie laboratoryjne'},{code:'ULTRASOUND',label:'USG — opis / wydruk'},{code:'MRI',label:'MRI — opis w PDF'},{code:'CT',label:'Tomografia — opis w PDF'},{code:'XRAY',label:'RTG — opis w PDF'},{code:'OTHER',label:'Inny dokument'}];
  protected readonly canChangeCategory=computed(()=>this.isPdf() && this.editableCategories.some(c=>c.code===this.document()?.typeCode));
  private formRevision=0;
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
      if (!current) {
        this.clearPreview();
        this.ecgSubscription?.unsubscribe();
        this.questionSubscription?.unsubscribe();
        this.ecg.set(null);
        this.messages.set([]);
        this.loadedDocumentId = '';
        this.loadedAssetId = '';
        return;
      }
      if (this.loadedDocumentId !== current.id) {
        this.metadataDirty.set(false);
        this.metadataHistory.set([]);this.metadataError.set('');
        this.loadedDocumentId = current.id;
        this.ecgSubscription?.unsubscribe();
        this.questionSubscription?.unsubscribe();
        this.ecg.set(null);
        this.ecgError.set('');
        this.ecgLoading.set(false);
        this.ecgCalibration.set({ samplingRate:null, unit:null });
        this.messages.set([]);
        this.chatError.set('');
        this.aiModel.set('');
        this.question.set('');
        if (this.isSignal()) this.loadEcg();
      }
      const signature = `${current.id}|${current.metadataRevision ?? 0}|${current.title}|${current.performedAt ?? ''}|${current.typeCode}`;
      if (signature !== this.formSignature && !this.metadataDirty()) {
        this.formSignature = signature;
        this.categoryCode.set(current.typeCode);this.formRevision=current.metadataRevision ?? 0;
        this.title.set(current.title);
        this.performedAt.set(this.dateInputValue(current.performedAt));
      }
      if (current.primaryAssetId && current.primaryAssetId !== this.loadedAssetId) {
        this.loadPreview(current.primaryAssetId);
      }
    });
    this.destroyRef.onDestroy(() => { this.clearPreview(); this.ecgSubscription?.unsubscribe(); this.questionSubscription?.unsubscribe(); });
  }

  protected loadEcg(calibration?: EcgCalibration):void {
    this.ecgSubscription?.unsubscribe();
    this.questionSubscription?.unsubscribe();
    if (calibration) this.ecgCalibration.set(calibration);
    this.ecg.set(null);
    this.ecgLoading.set(true);
    this.ecgError.set('');
    this.messages.set([]);
    this.aiModel.set('');
    this.chatError.set('');
    this.ecgSubscription = this.api.ecgPreview(this.workspace.role(), this.documentId(), this.ecgCalibration())
      .pipe(finalize(() => this.ecgLoading.set(false))).subscribe({
        next: data => this.ecg.set(data),
        error: error => this.ecgError.set(error?.error?.message ?? 'Nie udało się odczytać sygnału EKG.'),
      });
  }

  protected changeSelection(selection:EcgSelection):void {
    this.ecgSelection.set(selection);
    this.questionSubscription?.unsubscribe();
    this.messages.set([]);
    this.chatError.set('');
    this.aiModel.set('');
  }

  protected changeAudience(audience:'patient'|'clinician'):void {
    this.aiAudience.set(audience);
    this.questionSubscription?.unsubscribe();
    this.messages.set([]);
  }

  protected analyzeEcg():void {
    this.tab.set('chat');
    this.question.set(this.aiAudience() === 'clinician' ? 'Omów obliczone PQRST, HR, RR, PR, QRS, QT i QTc Fridericii wybranego fragmentu. Podaj kompletność pomiarów, zakres i elementy do weryfikacji na wykresie. Nie stawiaj diagnozy.' : 'Wyjaśnij prostym językiem pomiary tego fragmentu EKG: co oznaczają PQRST, HR, PR, QRS, QT, które wyniki są dostępne i czego nie można z nich wywnioskować.');
    this.ask();
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
    this.workspace.selectView(this.workspace.role() === 'patient' ? this.workspace.studyReturnView() : 'documents');
    void this.router?.navigateByUrl('/');
  }

  protected patientTitle(): string { const item = this.document(); return item ? patientStudyTitle(item) : ''; }
  protected patientStatus(): string { const item = this.document(); return item ? patientStudyStatus(item) : ''; }
  protected backLabel(): string { return this.workspace.studyReturnView() === 'overview' ? 'Wstecz na Start' : 'Wstecz do badań'; }

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
      this.workspace.role(), current.id, cleanTitle, this.performedAt(), this.categoryCode(), this.formRevision,
    ).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.metadataDirty.set(false);this.loadHistory();
        this.workspace.notify('Zapisano dane dokumentu.');
        this.workspace.load();
      },
      error: (error) => this.workspace.notify(
        error?.error?.message ?? 'Nie udało się zapisać zmian.',
      ),
    });
  }

  protected loadHistory():void {
    const id=this.document()?.id;if(!id)return;this.metadataError.set('');
    this.api.documentHistory(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({next:history=>{if(this.document()?.id===id)this.metadataHistory.set(history);},error:()=>this.metadataError.set('Nie udało się pobrać historii zmian.')});
  }
  protected resetMetadata():void {this.metadataDirty.set(false);this.formSignature='';this.workspace.load(true);}
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
    if (!current || !this.canAsk() || !content || this.asking()) return;
    const history = this.messages().slice(-6);
    this.messages.update((items) => [...items, { role: 'user', content }]);
    this.question.set('');
    this.chatError.set('');
    this.asking.set(true);
    this.questionSubscription = (this.isSignal()
      ? this.api.askEcg(this.workspace.role(), current.id, content, history, this.ecgCalibration(), this.ecgSelection(), this.aiAudience())
      : this.documents.ask(this.workspace.role(), current.id, content, history))
      .pipe(finalize(() => this.asking.set(false)))
      .subscribe({
        next: (response) => {
          this.aiModel.set(response.model);
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
    if (this.isSignal() || (!this.isPdf() && !this.isImage())) {
      this.previewLoading.set(false);
      this.previewError.set('');
      return;
    }
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

import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { StudyRecord } from '../../../core/models/medical';
import { TokenStorage } from '../../../core/services/token-storage';
import { WorkspaceStore } from '../../../core/services/workspace-store';
import { AppIcon } from '../app-icon/app-icon';

/** A preview always belongs to the supplied record; unsupported sources use a labelled icon. */
@Component({
  selector: 'app-study-preview',
  imports: [AppIcon],
  template: `
    <div class="record-preview" [class.compact]="compact()" [class.has-image]="imageUrl()">
      @if (imageUrl(); as url) {
        <img [src]="url" [alt]="'Rzeczywisty obraz z badania: ' + recordLabel()" />
        @if (!compact()) { <span class="image-caption">Środkowy obraz badania</span> }
      } @else {
        <span class="file-sheet"><app-icon [name]="icon()" [size]="compact() ? 25 : 42" /><b>{{ badge() }}</b></span>
        @if (!compact()) {
          <strong>{{ recordLabel() }}</strong>
          <small>{{ loading() ? 'Wczytywanie obrazu…' : isPdf() ? 'Oryginalny plik PDF' : imagingId() ? 'Podgląd obrazu niedostępny' : 'Podgląd pliku' }}</small>
        }
      }
    </div>
  `,
  styles: [`
    :host { display:block; min-width:0; }
    .record-preview { position:relative; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; min-height:200px; padding:20px; overflow:hidden; border:1px solid var(--border); border-radius:8px; background:#f0f7f6; text-align:center; }
    .file-sheet { position:relative; display:grid; width:76px; height:90px; place-items:center; border:1px solid #c5dcd9; border-radius:5px; color:var(--blue-strong); background:white; }
    .file-sheet b { position:absolute; bottom:6px; font-size:10px; letter-spacing:.06em; }
    strong { max-width:100%; overflow-wrap:anywhere; font-size:13px; color:var(--ink); }
    small { color:var(--muted); font-size:11px; }
    .has-image { padding:0; aspect-ratio:1; background:#071010; }
    img { display:block; width:100%; height:100%; max-height:420px; object-fit:contain; }
    .image-caption { position:absolute; left:8px; bottom:8px; padding:5px 8px; border-radius:4px; background:#061312cc; color:white; font-size:10px; }
    .compact { width:60px; height:66px; min-height:0; padding:0; border-radius:6px; }
    .compact .file-sheet { width:45px; height:55px; }
    .compact .file-sheet b { bottom:2px; font-size:8px; }
  `],
})
export class StudyPreview {
  readonly study = input<StudyRecord | null>(null);
  readonly imagingStudyId = input<string | null>(null);
  readonly typeCode = input('');
  readonly label = input('');
  readonly compact = input(false);
  private readonly http = inject(HttpClient, { optional: true });
  private readonly tokens = inject(TokenStorage);
  private readonly workspace = inject(WorkspaceStore);
  protected readonly imageUrl = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly imagingId = computed(() => this.imagingStudyId() || this.study()?.imagingStudyId || null);
  protected readonly recordLabel = computed(() => this.label() || this.study()?.typeName || 'Dokument');
  protected readonly isPdf = computed(() => this.study()?.mimeType === 'application/pdf' || /\.pdf$/i.test(this.study()?.originalFilename ?? ''));
  protected readonly badge = computed(() => this.isPdf() ? 'PDF' : ({ MRI:'MRI', MR:'MRI', CT:'CT', XRAY:'RTG', CR:'RTG', DX:'RTG', ULTRASOUND:'USG', US:'USG', EKG:'EKG', EEG:'EEG', LABORATORY:'WYNIK' } as Record<string,string>)[this.typeCode() || this.study()?.typeCode || ''] || 'PLIK');
  protected readonly icon = computed(() => ['EKG','EEG'].includes(this.badge()) ? 'activity' : ['MRI','CT','RTG','USG'].includes(this.badge()) && !this.isPdf() ? 'scan' : 'document');

  constructor() {
    effect((onCleanup) => {
      const imagingId = this.imagingId();
      const role = this.workspace.role();
      this.imageUrl.set(null);
      this.loading.set(false);
      if (!imagingId || !this.http) return;
      let objectUrl: string | null = null;
      this.loading.set(true);
      const subscription = this.http.get(`/api/v1/imaging-studies/${encodeURIComponent(imagingId)}/preview`, {
        responseType:'blob', headers:new HttpHeaders({ Authorization:`Bearer ${this.tokens.accessToken()}`, 'X-Workspace-Role':role }),
      }).subscribe({
        next:(blob) => { this.loading.set(false); if (!blob.type.startsWith('image/')) return; objectUrl = URL.createObjectURL(blob); this.imageUrl.set(objectUrl); },
        error:() => this.loading.set(false),
      });
      onCleanup(() => { subscription.unsubscribe(); if (objectUrl) URL.revokeObjectURL(objectUrl); });
    });
  }
}

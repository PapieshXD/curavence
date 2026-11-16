import { AfterViewInit, Component, ElementRef, EventEmitter, Input, OnDestroy, Output, ViewChild, signal } from '@angular/core';
import type Quill from 'quill';

export interface RichReviewChange { html: string; text: string }

@Component({
  selector: 'app-rich-review-editor',
  template: `<div class="rich-editor"><div #toolbar class="rich-toolbar" role="toolbar" [attr.aria-label]="'Formatowanie: ' + label">
    <span class="ql-formats"><select class="ql-header" aria-label="Styl akapitu"><option selected value="">Zwykły tekst</option><option value="2">Nagłówek</option></select></span>
    <span class="ql-formats"><button class="ql-bold" type="button" aria-label="Pogrubienie"></button><button class="ql-italic" type="button" aria-label="Kursywa"></button><button class="ql-underline" type="button" aria-label="Podkreślenie"></button></span>
    <span class="ql-formats"><button class="ql-list" value="ordered" type="button" aria-label="Lista numerowana"></button><button class="ql-list" value="bullet" type="button" aria-label="Lista punktowana"></button></span>
    <span class="ql-formats"><button class="ql-clean" type="button" aria-label="Usuń formatowanie"></button></span>
    <span class="ql-formats"><button class="ql-image" type="button" aria-label="Wstaw zdjęcie" title="Wstaw zdjęcie"></button></span>
  </div><div #editor class="rich-editor-area"></div></div>
  <input #fileInput type="file" accept="image/png,image/jpeg" hidden (change)="insertImage($event)">
  @if (error()) { <p class="rich-editor-error" role="alert">{{ error() }}</p> }`,
  styleUrl: './rich-review-editor.scss',
})
export class RichReviewEditor implements AfterViewInit, OnDestroy {
  @Input() label = 'Treść opisu';
  @Input() placeholder = 'Wpisz opis';
  @Output() readonly valueChange = new EventEmitter<RichReviewChange>();
  @ViewChild('toolbar') private toolbar!: ElementRef<HTMLElement>;
  @ViewChild('editor') private editor!: ElementRef<HTMLElement>;
  @ViewChild('fileInput') private fileInput!: ElementRef<HTMLInputElement>;
  private quill?: Quill;
  private html = '';
  private destroyed = false;
  readonly error = signal('');

  @Input() set value(html: string) {
    this.html = html || '';
    if (this.quill && this.quill.root.innerHTML !== this.html) this.setContent(this.html);
  }

  async ngAfterViewInit(): Promise<void> {
    const { default: QuillEditor } = await import('quill');
    if (this.destroyed) return;
    this.quill = new QuillEditor(this.editor.nativeElement, {
      theme: 'snow',
      placeholder: this.placeholder,
      modules: { toolbar: { container: this.toolbar.nativeElement, handlers: { image: () => this.fileInput.nativeElement.click() } } },
      formats: ['header', 'bold', 'italic', 'underline', 'list', 'image'],
    });
    this.quill.root.setAttribute('aria-label', this.label);
    if (this.html) this.setContent(this.html);
    this.quill.on('text-change', () => {
      if (!this.quill) return;
      this.html = this.quill.root.innerHTML;
      this.error.set('');
      this.valueChange.emit({ html: this.html, text: this.quill.getText().trim() });
    });
  }

  ngOnDestroy(): void { this.destroyed = true; this.quill = undefined; }

  private setContent(html: string): void {
    if (!this.quill) return;
    this.quill.setContents(this.quill.clipboard.convert({ html }), 'silent');
  }

  async insertImage(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.quill) return;
    if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 10 * 1024 * 1024) {
      this.error.set('Wybierz zdjęcie JPG lub PNG o wielkości do 10 MB.');
      return;
    }
    if (this.quill.root.querySelectorAll('img').length >= 3) {
      this.error.set('W jednym polu można umieścić maksymalnie 3 zdjęcia.');
      return;
    }
    try {
      const dataUrl = await this.resizeImage(file);
      const index = this.quill.getSelection(true)?.index ?? this.quill.getLength() - 1;
      this.quill.insertEmbed(index, 'image', dataUrl, 'user');
      this.quill.setSelection(index + 1, 0, 'silent');
    } catch {
      this.error.set('Nie udało się odczytać zdjęcia. Spróbuj użyć pliku JPG lub PNG.');
    }
  }

  private async resizeImage(file: File): Promise<string> {
    const bitmap = await createImageBitmap(file);
    try {
      const scale = Math.min(1, 1200 / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      const dataUrl = canvas.toDataURL('image/jpeg', 0.76);
      if (dataUrl.length > 600_000) throw new Error('Image is too large');
      return dataUrl;
    } finally { bitmap.close(); }
  }
}

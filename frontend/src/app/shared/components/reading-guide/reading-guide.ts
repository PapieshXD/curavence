import { Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription, finalize } from 'rxjs';
import { Api } from '../../../core/services/api';
import { WorkspaceStore } from '../../../core/services/workspace-store';
import { AssistantItem, AssistantSummary, MedicalTerm, PersonalReminder, ReadingGuideData } from '../../../core/models/reading-guide';
import { ExplainedText } from '../explained-text/explained-text';

@Component({
  selector: 'app-reading-guide', imports: [FormsModule, ExplainedText],
  templateUrl: './reading-guide.html', styleUrl: './reading-guide.scss',
})
export class ReadingGuide {
  readonly spacious = signal(false);
  readonly selectedBlocks = signal<ReadonlySet<string>>(new Set());
  readonly lastSelectedBlock = computed(() => this.guide()?.blocks.filter(block => this.selectedBlocks().has(block.id)).at(-1)?.id);
  readonly selectedText = computed(() => this.guide()?.blocks
    .filter(block => this.selectedBlocks().has(block.id)).map(block => block.text).join('\n') ?? '');
  toggleBlock(id: string): void {
    this.selectedBlocks.update(current => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }
  selectRow(event: MouseEvent, id: string): void {
    if ((event.target as HTMLElement).closest('button, input, a, .term-dialog, .term-backdrop') || window.getSelection()?.toString()) return;
    this.toggleBlock(id);
  }
  clearSelection(): void { this.selectedBlocks.set(new Set()); }
  askSelected(): void {
    if (this.selectedText()) this.ask('Co oznacza ten fragment mojego wyniku?', this.selectedText());
  }
  isSectionHeading(text: string): boolean {
    return /^(wyniki|opis badania|wnioski|zalecenia|podsumowanie|komentarz(?: \/ interpretacja techniczna)?|metoda badania)[:.]?$/iu.test(text.trim());
  }
  readonly documentId = input.required<string>();
  readonly fallbackText = input('');
  readonly originalAvailable = input(true);
  readonly original = output<void>();
  readonly guideLoaded = output<ReadingGuideData | null>();
  readonly explanationsChange = output<boolean>();
  readonly workspace = inject(WorkspaceStore);
  private readonly api = inject(Api);
  private readonly router = inject(Router, {optional: true});
  readonly guide = signal<ReadingGuideData | null>(null);
  readonly summary = signal<AssistantSummary | null>(null);
  readonly loading = signal(false); readonly generating = signal(false); readonly saving = signal(false);
  readonly visible = signal(true); readonly enabled = signal(true);
  readonly error = signal(''); readonly summaryError = signal(''); readonly reminderError = signal('');
  readonly reminders = signal<PersonalReminder[]>([]);
  readonly reminderTarget = signal<AssistantItem | null>(null);
  readonly editingReminder = signal<PersonalReminder | null>(null);
  description = ''; dueDate = ''; completed = false;
  private generation?: Subscription; private mutation?: Subscription;
  readonly ownReminders = computed(() => this.reminders().filter(r => r.documentId === this.documentId()));
  constructor() {
    effect(onCleanup => {
      const id = this.documentId(), role = this.workspace.role();
      this.workspace.realtimeRevision?.();
      this.generation?.unsubscribe(); this.mutation?.unsubscribe();
      this.selectedBlocks.set(new Set());
      this.guide.set(null); this.guideLoaded.emit(null); this.summary.set(null); this.error.set(''); this.summaryError.set(''); this.reminderError.set('');
      this.reminders.set([]); this.reminderTarget.set(null); this.editingReminder.set(null);
      this.generating.set(false); this.saving.set(false); this.loading.set(true);
      const subscriptions = new Subscription();
      subscriptions.add(this.api.readingGuideConfig().subscribe({next: config => {
        this.visible.set(config.enabled);
        if (!config.enabled) { this.loading.set(false); return; }
        subscriptions.add(this.api.readingGuide(role, id).pipe(finalize(() => this.loading.set(false))).subscribe({
          next: guide => { this.guide.set(guide); this.guideLoaded.emit(guide); },
          error: err => this.error.set(err?.error?.message ?? 'Nie można odczytać tekstu. Otwórz oryginalny plik.'),
        }));
        if (role === 'patient') subscriptions.add(this.api.personalReminders().subscribe({
          next: reminders => this.reminders.set(reminders),
          error: () => this.reminderError.set('Nie udało się wczytać osobistych przypomnień.'),
        }));
      }, error: () => { this.visible.set(false); this.loading.set(false); }}));
      onCleanup(() => { subscriptions.unsubscribe(); this.generation?.unsubscribe(); this.mutation?.unsubscribe(); });
    });
    inject(DestroyRef).onDestroy(() => { this.generation?.unsubscribe(); this.mutation?.unsubscribe(); });
  }
  generate(): void {
    if (this.generating()) return;
    this.generating.set(true); this.summaryError.set('');
    this.generation = this.api.assistantSummary(this.workspace.role(), this.documentId())
      .pipe(finalize(() => this.generating.set(false))).subscribe({next: summary => {
        if (summary.sourceVersion !== this.guide()?.sourceVersion) {
          this.summaryError.set('Tekst zmienił się. Otwórz badanie ponownie, aby odczytać aktualną wersję.'); return;
        }
        this.summary.set(summary);
      }, error: err => this.summaryError.set(err?.error?.message ?? 'Podsumowanie jest teraz niedostępne. Opis i słownik nadal działają.')});
  }
  toggleExplanations(value: boolean): void { this.enabled.set(value); this.explanationsChange.emit(value); }
  askTerm(event: {term: MedicalTerm; quote: string}): void {
    this.ask(`Co oznacza „${event.term.name}” w kontekście mojego wyniku?`, event.quote, event.term.id);
  }
  ask(question: string, sourceQuote: string, termId?: string): void {
    this.workspace.visitQuestionDraft.set({studyId: this.documentId(), question, sourceQuote, termId});
    this.workspace.selectView('visits'); void this.router?.navigateByUrl('/');
  }
  showSource(item: AssistantItem): void {
    const block = document.getElementById(this.sourceId(item.source.blockId));
    block?.scrollIntoView({behavior: 'smooth', block: 'center'}); block?.focus({preventScroll: true});
  }
  sourceId(blockId: string): string { return `source-${this.documentId()}-${blockId}`; }
  startReminder(item: AssistantItem): void {
    const existing = this.ownReminders().find(r => r.recommendationId === item.id);
    this.reminderTarget.set(item); this.editingReminder.set(existing ?? null);
    this.description = existing?.description ?? item.text.slice(0, 1000); this.dueDate = existing?.dueDate ?? '';
    this.completed = existing?.completed ?? false; this.reminderError.set('');
  }
  editReminder(reminder: PersonalReminder): void {
    this.editingReminder.set(reminder); this.reminderTarget.set(null);
    this.description = reminder.description; this.dueDate = reminder.dueDate; this.completed = reminder.completed;
    this.reminderError.set('');
  }
  saveReminder(): void {
    const existing = this.editingReminder(), target = this.reminderTarget();
    if ((!existing && !target) || !this.description.trim() || !this.dueDate || this.saving()) return;
    this.saving.set(true); this.reminderError.set('');
    const body = {documentId: this.documentId(), recommendationId: existing?.recommendationId ?? target!.id,
      description: this.description.trim(), sourceQuote: existing?.sourceQuote ?? target!.source.quote,
      dueDate: this.dueDate, completed: this.completed};
    this.mutation = this.api.savePersonalReminder(body, existing?.id).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: saved => {
        if (existing) this.reminders.update(items => items.map(r => r.id === existing.id ? {...body, id: existing.id} : r));
        else if (saved) this.reminders.update(items => [...items.filter(r => r.id !== saved.id), saved]);
        this.cancelReminder(); this.workspace.notify('Zapisano osobiste przypomnienie.');
      }, error: err => this.reminderError.set(err?.error?.message ?? 'Nie udało się zapisać przypomnienia.'),
    });
  }
  cancelReminder(): void { this.reminderTarget.set(null); this.editingReminder.set(null); }
}

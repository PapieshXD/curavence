import { Component, ElementRef, computed, inject, input, output, signal, OnDestroy } from '@angular/core';
import { MedicalTerm, ReadingBlock } from '../../../core/models/reading-guide';

export function readingSegments(block: ReadingBlock): {text: string; termId?: string}[] {
  const segments: {text: string; termId?: string}[] = [];
  let cursor = 0;
  for (const occurrence of block.occurrences) {
    if (occurrence.start < cursor || occurrence.end > block.text.length || occurrence.start >= occurrence.end) continue;
    if (occurrence.start > cursor) segments.push({text: block.text.slice(cursor, occurrence.start)});
    segments.push({text: block.text.slice(occurrence.start, occurrence.end), termId: occurrence.termId});
    cursor = occurrence.end;
  }
  if (cursor < block.text.length) segments.push({text: block.text.slice(cursor)});
  return segments;
}
let nextId = 0;
@Component({
  selector: 'app-explained-text',
  templateUrl: './explained-text.html', styleUrl: './explained-text.scss',
  host: {'(document:keydown.escape)': 'close()', '(window:resize)': 'position()', '(window:scroll)': 'position()'},
})
export class ExplainedText implements OnDestroy {
  readonly blocks = input<ReadingBlock[]>([]);
  readonly terms = input<MedicalTerm[]>([]);
  readonly enabled = input(true);
  readonly heading = input(false);
  readonly showList = input(true);
  readonly listBlocks = input<ReadingBlock[]>([]);
  readonly question = output<{term: MedicalTerm; quote: string}>();
  readonly active = signal<MedicalTerm | null>(null);
  readonly panelId = `term-panel-${++nextId}`;
  readonly left = signal(0); readonly top = signal(0);
  private trigger: HTMLElement | null = null;
  private quote = '';
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly rendered = computed(() => this.blocks().map(block => ({...block, segments: readingSegments(block)})));
  open(id: string, event: Event, quote: string): void {
    const term = this.terms().find(t => t.id === id);
    if (!term) return;
    this.trigger = event.currentTarget as HTMLElement;
    this.quote = quote; this.active.set(term); this.position();
    requestAnimationFrame(() => this.element.nativeElement.querySelector<HTMLElement>('.term-close')?.focus());
  }
  position(): void {
    if (!this.active() || !this.trigger) return;
    const box = this.trigger.getBoundingClientRect();
    this.left.set(Math.max(12, Math.min(box.left, window.innerWidth - 372)));
    this.top.set(Math.max(12, Math.min(box.bottom + 10, window.innerHeight - 350)));
  }
  close(): void {
    if (!this.active()) return;
    this.active.set(null); this.trigger?.focus();
  }
  ask(): void {
    const term = this.active();
    if (term) this.question.emit({term, quote: this.quote});
    this.close();
  }
  trap(event: KeyboardEvent): void {
    if (event.key !== 'Tab') return;
    const controls = [...this.element.nativeElement.querySelectorAll<HTMLElement>('.term-dialog button, .term-dialog a')];
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
  listQuote(id: string): string {
    return [...this.blocks(), ...this.listBlocks()].find(b => b.occurrences.some(o => o.termId === id))?.text ?? '';
  }
  ngOnDestroy(): void { this.active.set(null); }
}

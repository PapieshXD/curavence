import { TestBed } from '@angular/core/testing';
import { ExplainedText, readingSegments } from './explained-text';
import { MedicalTerm } from '../../../core/models/reading-guide';

const term: MedicalTerm = {id: 'cyst', name: 'Torbiel', aliases: [], category: 'imaging', contextualAliases: [],
  definition: 'Definicja testowa', sources: [{title: 'Test source', url: 'https://example.com'}], reviewStatus: 'APPROVED', reviewedAt: '2026-11-01', reviewedBy: 'Test'};
describe('ExplainedText', () => {
  it('preserves spaces, line breaks, Unicode, values and negations', () => {
    const text = '😀 Nie stwierdzono torbieli.\r\n  12,5 mm\n';
    const start = text.indexOf('torbieli');
    const segments = readingSegments({id: 'b0', text, occurrences: [{start, end: start + 8, termId: 'cyst'}]});
    expect(segments.map(s => s.text).join('')).toBe(text);
    expect(segments.find(s => s.termId)?.text).toBe('torbieli');
  });
  it('opens with a button, closes on Escape, restores focus and emits the source with a question', async () => {
    await TestBed.configureTestingModule({imports: [ExplainedText]}).compileComponents();
    const fixture = TestBed.createComponent(ExplainedText);
    fixture.componentRef.setInput('terms', [term]);
    fixture.componentRef.setInput('blocks', [{id: 'b0', text: 'Torbiel.', occurrences: [{start: 0, end: 7, termId: 'cyst'}]}]);
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('.term') as HTMLButtonElement;
    button.click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="dialog"]').textContent).toContain('Definicja testowa');
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true})); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeNull();
    const emitted: unknown[] = []; fixture.componentInstance.question.subscribe(value => emitted.push(value));
    button.click(); fixture.detectChanges();
    fixture.componentInstance.ask();
    expect(emitted).toEqual([{term, quote: 'Torbiel.'}]);
    fixture.componentRef.setInput('enabled', false); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.term')).toBeNull();
    expect(fixture.nativeElement.querySelector('.reading-text').textContent).toBe('Torbiel.');
  });
});

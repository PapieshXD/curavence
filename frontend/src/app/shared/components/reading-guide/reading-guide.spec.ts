import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { ReadingGuide } from './reading-guide';
import { Api } from '../../../core/services/api';
import { WorkspaceStore } from '../../../core/services/workspace-store';
import { signal } from '@angular/core';
import { ReadingGuideData, AssistantSummary } from '../../../core/models/reading-guide';

function guide(id: string): ReadingGuideData { return {enabled: true, documentId: id, sourceVersion: id,
  dictionaryVersion: 'v', blocks: [{id: 'b0', text: 'Kontrola za rok.', occurrences: []}], terms: []}; }
describe('ReadingGuide lifecycle', () => {
  it('keeps the existing text when the feature flag is disabled', async () => {
    const api = {readingGuideConfig: () => of({enabled: false})};
    const workspace = {role: signal('patient')};
    await TestBed.configureTestingModule({imports: [ReadingGuide], providers: [{provide: Api, useValue: api}, {provide: WorkspaceStore, useValue: workspace}]}).compileComponents();
    const fixture = TestBed.createComponent(ReadingGuide);
    fixture.componentRef.setInput('documentId', 'doc');
    fixture.componentRef.setInput('fallbackText', 'Oryginalny odczytany opis.'); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Oryginalny odczytany opis.');
    expect(fixture.nativeElement.querySelector('.assistant-overview')).toBeNull();
  });
  it('selects fragments by click, keeps source order and passes the quote to a visit', async () => {
    const data = guide('doc');
    data.blocks = [{id: 'a', text: 'Pierwszy fragment.', occurrences: []}, {id: 'b', text: 'Drugi fragment.', occurrences: []}];
    const api = {readingGuideConfig: () => of({enabled: true}), readingGuide: () => of(data), personalReminders: () => of([])};
    const workspace = {role: signal('patient'), visitQuestionDraft: signal<unknown>(null), selectView: () => {}};
    await TestBed.configureTestingModule({imports: [ReadingGuide], providers: [{provide: Api, useValue: api}, {provide: WorkspaceStore, useValue: workspace}]}).compileComponents();
    const fixture = TestBed.createComponent(ReadingGuide);
    fixture.componentRef.setInput('documentId', 'doc'); fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.selectable-row');
    rows[1].querySelector('.reading-text span').click(); rows[0].querySelector('.reading-text span').click(); fixture.detectChanges();
    expect(fixture.componentInstance.selectedText()).toBe('Pierwszy fragment.\nDrugi fragment.');
    expect(fixture.nativeElement.querySelectorAll('.fragment-toggle[aria-pressed="true"]').length).toBe(2);
    fixture.nativeElement.querySelector('.selection-buttons .primary-button').click();
    expect(workspace.visitQuestionDraft()).toEqual({studyId: 'doc', question: 'Co oznacza ten fragment mojego wyniku?', sourceQuote: 'Pierwszy fragment.\nDrugi fragment.', termId: undefined});
    fixture.nativeElement.querySelector('.selection-buttons .secondary-button').click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.selection-actions')).toBeNull();
  });
  it('discards a summary after switching documents and opens the existing reminder', async () => {
    const delayed = new Subject<AssistantSummary>();
    const api = {readingGuideConfig: () => of({enabled: true}), readingGuide: (_: string, id: string) => of(guide(id)),
      assistantSummary: () => delayed, personalReminders: () => of([{id: 'r', documentId: 'new', recommendationId: 'recommendation',
        description: 'Moje przypomnienie', sourceQuote: 'Kontrola za rok.', dueDate: '2027-11-01', completed: false}])};
    const workspace = {role: signal('patient'), visitQuestionDraft: signal(null), selectView: () => {}, notify: () => {}};
    await TestBed.configureTestingModule({imports: [ReadingGuide], providers: [{provide: Api, useValue: api}, {provide: WorkspaceStore, useValue: workspace}]}).compileComponents();
    const fixture = TestBed.createComponent(ReadingGuide); fixture.componentRef.setInput('documentId', 'old'); fixture.detectChanges();
    fixture.componentInstance.generate();
    fixture.componentRef.setInput('documentId', 'new'); fixture.detectChanges();
    delayed.next({sourceVersion: 'old'} as AssistantSummary);
    expect(fixture.componentInstance.summary()).toBeNull();
    fixture.componentInstance.startReminder({id: 'recommendation', text: 'Kontrola za rok.', deadlineText: null, source: {blockId: 'b0', quote: 'Kontrola za rok.'}});
    expect(fixture.componentInstance.editingReminder()?.id).toBe('r');
    expect(fixture.componentInstance.description).toBe('Moje przypomnienie');
  });
});

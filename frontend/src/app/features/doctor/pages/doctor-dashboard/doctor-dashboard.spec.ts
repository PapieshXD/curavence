import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { DoctorDashboard } from './doctor-dashboard';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { Api } from '../../../../core/services/api';
import { StudyRecord, WorkspaceData } from '../../../../core/models/medical';

const study = (id: string, status = 'AWAITING_REVIEW', imagingStudyId: string | null = null): StudyRecord => ({
  id, patientId: `patient-${id}`, patientName: `Pacjent ${id}`, title: `Badanie ${id}`, typeCode: 'MR', typeName: 'Rezonans magnetyczny',
  clinicalStatus: status, imagingStudyId, performedAt: null, description: null, sourceName: null, ingestionStatus: 'READY',
  primaryAssetId: null, originalFilename: null, mimeType: null, sizeBytes: null, hasExtractedText: false, documentProcessingStatus: null,
});

describe('DoctorDashboard data binding', () => {
  let workspace: WorkspaceStore;
  let api: any;
  let router: any;
  beforeEach(() => {
    api = { imagingStudy: vi.fn(() => of({ study: { id: 'imaging-a', instanceCount: 0 }, series: [] })), latestImagingAnalysis: vi.fn(() => of(null)), rewriteReview: vi.fn() };
    router = { navigate: vi.fn() };
    TestBed.configureTestingModule({ imports: [DoctorDashboard], providers: [{provide: Api, useValue: api}, {provide: Router, useValue: router}] });
    workspace = TestBed.inject(WorkspaceStore);
    workspace.role.set('doctor');
    workspace.data.set({ studies: [study('a', 'AWAITING_REVIEW', 'imaging-a'), study('b', 'SIGNED'), study('c')], reviews: [], grants: [], auditEvents: [] } as unknown as WorkspaceData);
  });
  async function render() {
    const fixture = TestBed.createComponent(DoctorDashboard);
    await fixture.whenStable();
    return fixture;
  }
  it('loads actual imaging ids without static MRI or fabricated findings', async () => {
    const fixture = await render();
    expect(api.imagingStudy).toHaveBeenCalledWith('doctor', 'imaging-a');
    expect(api.latestImagingAnalysis).toHaveBeenCalledWith('doctor', 'imaging-a');
    expect(fixture.nativeElement.querySelector('img[src*="brain-mri-preview"]')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('42 z 180');
    (fixture.componentInstance as any).openViewer();
    expect(router.navigate).toHaveBeenCalledWith(['/imaging-studies', 'imaging-a', 'viewer']);
  });
  it('filters signed studies and search results', async () => {
    const fixture = await render();
    (fixture.componentInstance as any).queueFilter.set('signed');
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('.case-row')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.case-header').textContent).toContain('Badanie b');
    workspace.search.set('nieistniejące');
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.active-case')).toBeNull();
  });
  it('cancels stale imaging requests when switching to a document', async () => {
    const pending = new Subject<any>(); api.imagingStudy.mockReturnValue(pending);
    const fixture = await render();
    (fixture.componentInstance as any).selectStudy(workspace.data()!.studies[1]);
    (fixture.componentInstance as any).caseTab.set('images');
    await fixture.whenStable();
    pending.next({study: {id:'imaging-a'}, series: []});
    await fixture.whenStable();
    expect((fixture.componentInstance as any).imaging()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Brak obrazów DICOM / NIfTI');
  });
  it('scopes saved reviews and audit to the selected study and opens its patient', async () => {
    workspace.data.update(data => ({...data!, reviews: [
      {id:'r1',studyId:'a',summary:'Właściwy opis',updatedAt:'2026-09-30'},
      {id:'r2',studyId:'b',summary:'Obcy opis',updatedAt:'2026-09-30'},
    ], auditEvents: [{id:'e1',resourceId:'imaging-a',createdAt:'2026-09-30'}, {id:'e2',resourceId:'b',createdAt:'2026-09-30'}]} as WorkspaceData));
    const fixture = await render();
    expect(fixture.nativeElement.textContent).toContain('Właściwy opis');
    expect(fixture.nativeElement.textContent).not.toContain('Obcy opis');
    expect((fixture.componentInstance as any).audit().map((event:any) => event.id)).toEqual(['e1']);
    (fixture.componentInstance as any).openPatient();
    expect(workspace.selectedPatientId()).toBe('patient-a');
  });
  it('keeps unsaved text separate between cases and during workspace refresh', async () => {
    const fixture = await render(); const component = fixture.componentInstance as any;
    component.summaryText.set('Opis A');
    component.selectStudy(workspace.data()!.studies[1]); await fixture.whenStable();
    expect(component.summaryText()).toBe('');
    component.summaryText.set('Opis B');
    component.selectStudy(workspace.data()!.studies[0]); await fixture.whenStable();
    expect(component.summaryText()).toBe('Opis A');
    workspace.data.update(data => ({...data!, studies: [...data!.studies]})); await fixture.whenStable();
    expect(component.summaryText()).toBe('Opis A');
  });
  it('keeps formatting in each case draft and preserves inserted images after AI rewrite', async () => {
    api.rewriteReview.mockReturnValue(of({summary: 'Poprawiony opis.', conclusion: ''}));
    const fixture = await render(); const component = fixture.componentInstance as any;
    const image = '<img src="data:image/png;base64,iVBORw0KGgo=">';
    component.updateSummary({text: 'Roboczy opis', html: `<p><strong>Roboczy opis</strong>${image}</p>`});
    component.selectStudy(workspace.data()!.studies[1]); await fixture.whenStable();
    component.selectStudy(workspace.data()!.studies[0]); await fixture.whenStable();
    expect(component.summaryHtml()).toContain('<strong>Roboczy opis</strong>');
    component.rewriteReview(); await fixture.whenStable();
    component.applyRewrite(); await fixture.whenStable();
    expect(component.summaryHtml()).toContain('<p>Poprawiony opis.</p>');
    expect(component.summaryHtml()).toContain(image);
  });
  it('shows an AI suggestion for review without saving or overwriting the draft', async () => {
    api.rewriteReview.mockReturnValue(of({summary: 'Widoczna zmiana 6 mm.', conclusion: 'Zaleca się kontrolę.'}));
    const fixture = await render(); const component = fixture.componentInstance as any;
    component.summaryText.set('widac zmiane 6mm'); component.conclusion.set('kontrola');
    component.rewriteReview(); await fixture.whenStable();
    expect(api.rewriteReview).toHaveBeenCalledWith('doctor', 'a', 'widac zmiane 6mm', 'kontrola');
    expect(component.summaryText()).toBe('widac zmiane 6mm');
    expect(fixture.nativeElement.textContent).toContain('Widoczna zmiana 6 mm.');
    component.applyRewrite(); await fixture.whenStable();
    expect(component.summaryText()).toBe('Widoczna zmiana 6 mm.');
    expect(component.conclusion()).toBe('Zaleca się kontrolę.');
    expect(fixture.nativeElement.querySelector('.rewrite-preview')).toBeNull();
  });
  it('ignores a suggestion returned after selecting another study', async () => {
    const pending = new Subject<any>(); api.rewriteReview.mockReturnValue(pending);
    const fixture = await render(); const component = fixture.componentInstance as any;
    component.summaryText.set('Szkic A'); component.rewriteReview();
    component.selectStudy(workspace.data()!.studies[1]); await fixture.whenStable();
    pending.next({summary:'Obcy opis',conclusion:''}); await fixture.whenStable();
    expect(component.rewriteSuggestion()).toBeNull();
    expect(component.summaryText()).toBe('');
  });
});

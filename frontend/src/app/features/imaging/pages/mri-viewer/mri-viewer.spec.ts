import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { MriViewer } from './mri-viewer';

describe('MriViewer navigation', () => {
  const series = [
    { id: 'scout', seriesInstanceUid: '1', description: 'SCOUT', imageCount: 1 },
    { id: 'brain', seriesInstanceUid: '2', description: 'FLAIR', imageCount: 25 },
  ];
  let api: any;
  let viewer: any;
  beforeEach(() => {
    api = { imagingOverview: vi.fn().mockReturnValue(of({ selectedSeriesId: 'brain', series: [] })),
      saveImagingPreferences: vi.fn().mockReturnValue(of({})) };
    TestBed.configureTestingModule({ providers: [
      { provide: Api, useValue: api },
      { provide: WorkspaceStore, useValue: { role: signal('patient'), notify: vi.fn(), data: signal(null) } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
      { provide: Router, useValue: { navigate: vi.fn() } },
    ] });
    viewer = TestBed.runInInjectionContext(() => new MriViewer());
    viewer.studyId.set('study'); viewer.details.set({ series }); viewer.preferencesReady.set(true);
  });
  afterEach(() => viewer.ngOnDestroy());
  it('opens diagnostic images and restores all hidden material with one preference write', () => {
    viewer.hiddenSeries.set(['brain', 'scout']);
    viewer.restoreSeries();
    expect(viewer.mode()).toBe('sheet');
    expect(viewer.hiddenSeries()).toEqual([]);
    expect(api.imagingOverview).toHaveBeenCalledWith('patient', 'study', 'brain');
    expect(api.saveImagingPreferences).toHaveBeenCalledExactlyOnceWith('patient', 'study', [], 'sheet');
  });
  it('retains the selected series when returning to the contact sheet', () => {
    viewer.selectedSeriesId.set('scout'); viewer.openSheet();
    expect(api.imagingOverview).toHaveBeenCalledWith('patient', 'study', 'scout');
  });
  it('keeps the patient in a simple viewer when the preview service is unavailable', () => {
    api.imagingOverview.mockReturnValue(throwError(() => ({ status: 404 })));
    viewer.restoreSeries();
    expect(viewer.mode()).toBe('slice');
    expect(viewer.overviewFallback()).toContain('Plansza');
    expect(viewer.loading()).toBe(false);
  });
  it('does not bypass access errors with the DICOM fallback', () => {
    api.imagingOverview.mockReturnValue(throwError(() => ({ status: 403 })));
    viewer.restoreSeries();
    expect(viewer.overviewFallback()).toBe('');
    expect(viewer.error()).not.toBe('');
  });
});

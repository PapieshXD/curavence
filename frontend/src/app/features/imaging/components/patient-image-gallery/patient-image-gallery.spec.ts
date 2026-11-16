import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { PatientImageGallery } from './patient-image-gallery';

describe('PatientImageGallery', () => {
  let api: any;
  beforeEach(() => {
    api = {
      imagingOverview: vi.fn((_role, _study, id) => of({ series: [{ id, frames: Array.from({ length: id === 'first' ? 2 : 3 }, (_, index) => ({ index, imageUrl: `/${id}/${index}` })) }] })),
      imagingPreviewImage: vi.fn(() => of(new Blob(['image']))),
    };
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    TestBed.configureTestingModule({ imports: [PatientImageGallery], providers: [
      { provide: Api, useValue: api },
      { provide: WorkspaceStore, useValue: { role: signal('patient') } },
    ] });
  });
  afterEach(() => vi.restoreAllMocks());
  async function create() {
    const fixture = TestBed.createComponent(PatientImageGallery);
    fixture.componentRef.setInput('studyId', 'study');
    fixture.componentRef.setInput('series', [{ id: 'first', imageCount: 2 }, { id: 'second', imageCount: 3 }]);
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    return fixture;
  }
  it('automatically displays every layer from every series in one numbered grid', async () => {
    const fixture = await create();
    expect(fixture.nativeElement.querySelectorAll('.layer img')).toHaveLength(5);
    expect(Array.from(fixture.nativeElement.querySelectorAll('.number'), (node: any) => node.textContent.trim())).toEqual(['1', '2', '3', '4', '5']);
    expect(api.imagingPreviewImage).toHaveBeenCalledTimes(5);
    expect(fixture.nativeElement.querySelector('select')).toBeNull();
  });
  it('moves across the series boundary without requiring another selection', async () => {
    const fixture = await create(); const component = fixture.componentInstance as any;
    component.selectedKey.set('first:1'); component.move(component.selectedIndex() + 1);
    expect(component.selected().key).toBe('second:0');
    expect(component.selectedIndex()).toBe(2);
  });
  it('shows individual source images without patient guidance in clinical mode', async () => {
    const fixture = await create();
    fixture.componentRef.setInput('clinical', true);
    await fixture.whenStable();
    expect((fixture.componentInstance as any).originals()).toBe(true);
    expect(fixture.nativeElement.querySelector('.patient-companion')).toBeNull();
    expect(fixture.nativeElement.querySelector('.fusion-toolbar')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.layer')).toHaveLength(5);
  });
  it('plays across series and stops when the viewer is hidden', async () => {
    const fixture = await create(); const component = fixture.componentInstance as any;
    vi.useFakeTimers();
    try {
      component.selectedKey.set('first:1'); component.togglePlayback();
      vi.advanceTimersByTime(500);
      expect(component.selected().key).toBe('second:0');
      expect(component.playing()).toBe(true);
      fixture.componentRef.setInput('active', false); fixture.detectChanges();
      expect(component.playing()).toBe(false);
      vi.advanceTimersByTime(1000);
      expect(component.selected().key).toBe('second:0');
    } finally { fixture.destroy(); vi.useRealTimers(); }
  });
  it('preserves missing layers and successful images when one series fails', async () => {
    const normal = api.imagingOverview.getMockImplementation();
    api.imagingOverview.mockImplementation((role: string, study: string, id: string) => id === 'first' ? throwError(() => ({ status: 503 })) : normal(role, study, id));
    const fixture = await create();
    expect(fixture.nativeElement.querySelectorAll('.layer')).toHaveLength(5);
    expect(fixture.nativeElement.querySelectorAll('.layer img')).toHaveLength(3);
    expect(fixture.nativeElement.textContent).toContain('Część obrazów nie została wczytana');
    fixture.destroy();
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(3);
  });
});

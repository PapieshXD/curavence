import { TestBed } from '@angular/core/testing';
import { ImagingSeries } from '../../../../core/models/imaging';
import { StudySeriesRail } from './study-series-rail';

describe('StudySeriesRail', () => {
  it('keeps hidden material in the catalog and exposes restoration without changing analysis scope', async () => {
    await TestBed.configureTestingModule({imports: [StudySeriesRail]}).compileComponents();
    const fixture = TestBed.createComponent(StudySeriesRail);
    const catalog = [
      {id: 'diagnostic', seriesInstanceUid: 'd', description: 'FLAIR', imageCount: 24},
      {id: 'technical', seriesInstanceUid: 't', description: 'SCOUT', imageCount: 3},
    ] as ImagingSeries[];
    fixture.componentRef.setInput('series', catalog);
    fixture.componentRef.setInput('hidden', ['technical']);
    fixture.componentRef.setInput('results', [{seriesInstanceUid: 'd', analysisStatus: 'ANALYZED'}]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('article').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('24 obrazów');
    expect(fixture.nativeElement.textContent).not.toContain('FLAIR');
    const restore = vi.fn();
    fixture.componentInstance.restore.subscribe(restore);
    const button = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(item => item.textContent?.includes('Pokaż wszystkie obrazy'))!;
    button.click();
    expect(restore).toHaveBeenCalledOnce();
    expect(catalog).toHaveLength(2);
    fixture.componentRef.setInput('hidden', []);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('article').length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Obrazy pomocnicze');
    expect(fixture.nativeElement.textContent).toContain('Analiza dostępna');
  });
});

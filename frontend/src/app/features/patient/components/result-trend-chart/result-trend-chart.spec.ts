import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ClinicalResultParameter } from '../../../../core/models/medical';
import { ResultTrendChart } from './result-trend-chart';

describe('ResultTrendChart', () => {
  let fixture: ComponentFixture<ResultTrendChart>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ResultTrendChart] }).compileComponents();
    fixture = TestBed.createComponent(ResultTrendChart);
  });

  it('builds the line and points from an arbitrary history series', () => {
    const parameter: ClinicalResultParameter = {
      id: 'glucose',
      name: 'Glukoza',
      value: 98,
      numericValue: 98,
      unit: 'mg/dl',
      observedAt: '2026-08-01T08:00:00Z',
      referenceRange: { low: 70, high: 99 },
      history: [
        { observedAt: '2026-05-01T08:00:00Z', value: 87 },
        { observedAt: '2026-06-01T08:00:00Z', value: 92 },
        { observedAt: '2026-07-01T08:00:00Z', value: 95 },
      ],
    };
    fixture.componentRef.setInput('parameter', parameter);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('polyline')?.getAttribute('points')).toContain(',');
    expect(fixture.nativeElement.querySelectorAll('circle')).toHaveLength(4);
  });

  it('waits for at least two measurements', () => {
    fixture.componentRef.setInput('parameter', { id: 'crp', name: 'CRP', value: 2.1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.trend-chart')).toBeNull();
  });
});

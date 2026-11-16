import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ClinicalResultParameter } from '../../../../core/models/medical';
import { ResultRangeChart } from './result-range-chart';

describe('ResultRangeChart', () => {
  let fixture: ComponentFixture<ResultRangeChart>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ResultRangeChart] }).compileComponents();
    fixture = TestBed.createComponent(ResultRangeChart);
  });

  it('renders a reusable range visualization from input data', () => {
    const parameter: ClinicalResultParameter = {
      id: 'tsh',
      name: 'TSH',
      value: 5.2,
      unit: 'mIU/l',
      referenceRange: { low: 0.27, high: 4.2 },
    };
    fixture.componentRef.setInput('parameter', parameter);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.range-chart')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.range-point.high')).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('5,2 mIU/l');
  });

  it('does not invent a range when none was supplied', () => {
    fixture.componentRef.setInput('parameter', { id: 'note', name: 'Opis', value: 'ujemny' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.range-chart')).toBeNull();
  });
});

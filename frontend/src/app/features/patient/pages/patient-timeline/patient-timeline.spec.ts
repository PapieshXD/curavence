import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PatientTimeline } from './patient-timeline';

describe('PatientTimeline', () => {
  let component: PatientTimeline;
  let fixture: ComponentFixture<PatientTimeline>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PatientTimeline]
    })
    .compileComponents();

    fixture = TestBed.createComponent(PatientTimeline);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});

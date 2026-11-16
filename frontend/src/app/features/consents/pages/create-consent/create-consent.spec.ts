import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CreateConsent } from './create-consent';

describe('CreateConsent', () => {
  let component: CreateConsent;
  let fixture: ComponentFixture<CreateConsent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreateConsent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CreateConsent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});

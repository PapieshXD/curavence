import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ConsentList } from './consent-list';

describe('ConsentList', () => {
  let component: ConsentList;
  let fixture: ComponentFixture<ConsentList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ConsentList]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ConsentList);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});

import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';
import { Visits } from './visits';
import { VisitActionCard } from './visit-action';
import { Api } from '../../core/services/api';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { Visit, VisitAction } from '../../core/models/visit';

const action = (): VisitAction => ({id:'a',description:'Badanie kontrolne',dueDate:'2026-12-01',responsible:'Pacjent',completed:false,
  patientStatus:'TODO',performedOn:null,patientNote:'',obstacle:null,reportedBy:null,reportedAt:null,reportedName:null,
  discussedBy:null,discussedAt:null,discussedName:null,revision:0,studyIds:[]});
const visit = (id: string): Visit => ({id,patientId:'p',patientName:'Jan',reason:'Kontrola',symptoms:'',questions:'',visitDate:null,
  grantId:null,accessStatus:'DRAFT',recipientEmail:null,expiresAt:null,summary:'',approvedAt:null,approvedBy:null,createdAt:'2026-11-01',
  studyIds:[],actions:[],previousVisitId:null,includePreviousPlan:false,previousPlan:null,newStudyIds:[]});

describe('Visit continuity', () => {
  let api: any; let workspace: WorkspaceStore;
  beforeEach(async () => {
    api={knowledgeConfig:vi.fn(()=>of({enabled:false})),libraryConfig:vi.fn(()=>of({searchEnabled:false,exportsEnabled:false})),visits:vi.fn(()=>of([])),saveVisit:vi.fn(()=>of({id:'new'})),updateVisit:vi.fn(()=>of(undefined))};
    TestBed.configureTestingModule({imports:[Visits,VisitActionCard],providers:[provideRouter([]),{provide:Api,useValue:api}]});
    await TestBed.compileComponents();
    workspace=TestBed.inject(WorkspaceStore);
  });
  it('ignores a response from the previous role', async () => {
    const pending=new Subject<Visit[]>();api.visits.mockReturnValueOnce(pending);
    const fixture=TestBed.createComponent(Visits);await fixture.whenStable();
    workspace.role.set('doctor');await fixture.whenStable();
    pending.next([visit('private-patient-draft')]);await fixture.whenStable();
    expect(fixture.componentInstance.visits()).toEqual([]);
  });
  it('saves the explicitly selected previous visit and consent', async () => {
    const fixture=TestBed.createComponent(Visits);await fixture.whenStable();
    const component=fixture.componentInstance;component.edit(null);component.reason='Kontrola';
    component.previousVisitId='chosen';component.includePreviousPlan=true;component.save();
    expect(api.saveVisit).toHaveBeenCalledWith('patient',null,expect.objectContaining({previousVisitId:'chosen',includePreviousPlan:true}));
  });
  it('keeps unsaved doctor plan text during realtime refresh', async () => {
    const saved=visit('v');api.visits.mockReturnValue(of([saved]));
    const fixture=TestBed.createComponent(Visits);await fixture.whenStable();
    const component=fixture.componentInstance;component.open(saved);component.summary='Unsaved plan';component.planDirty.set(true);
    workspace.realtimeRevision.update(n=>n+1);await fixture.whenStable();
    expect(component.summary).toBe('Unsaved plan');
  });
  it('does not guess a baseline from list ordering', async () => {
    const fixture=TestBed.createComponent(Visits);await fixture.whenStable();
    const current=visit('new');current.previousVisitId='explicit';current.newStudyIds=['new-study'];
    expect(fixture.componentInstance.newSincePrevious(current,'new-study')).toBe(true);
    expect(fixture.componentInstance.newSincePrevious(current,'old-study')).toBe(false);
  });
  it('preserves an unsaved progress draft when the same revision is refreshed', async () => {
    const fixture=TestBed.createComponent(VisitActionCard);fixture.componentRef.setInput('action',action());await fixture.whenStable();
    fixture.componentInstance.note='My draft';fixture.componentRef.setInput('action',action());await fixture.whenStable();
    expect(fixture.componentInstance.note).toBe('My draft');
    fixture.componentRef.setInput('action',{...action(),revision:1,patientNote:'Updated elsewhere'});await fixture.whenStable();
    expect(fixture.componentInstance.note).toBe('Updated elsewhere');
  });
});

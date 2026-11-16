import {TestBed} from '@angular/core/testing';
import {of,Subject} from 'rxjs';
import {Api} from '../../core/services/api';
import {WorkspaceStore} from '../../core/services/workspace-store';
import {WorkspaceData} from '../../core/models/medical';
import {ExportPanel} from './export-panel';
import {DocumentLibrary} from './document-library';
import {LibraryPage,ExportJob} from '../../core/models/readiness';

describe('Daily readiness flows',()=>{
  let api:any;let workspace:WorkspaceStore;
  beforeEach(async()=>{
    api={knowledgeConfig:vi.fn(()=>of({enabled:false})),libraryConfig:vi.fn(()=>of({searchEnabled:true,exportsEnabled:true})),searchDocuments:vi.fn(()=>of({items:[],total:0,page:0,pageSize:25})),createExport:vi.fn(()=>of({id:'job',status:'READY',format:'PDF',expiresAt:'2026-12-01',errorMessage:null})),exportStatus:vi.fn(()=>of({id:'job',status:'READY',format:'PDF',expiresAt:'2026-12-01',errorMessage:null}))};
    TestBed.configureTestingModule({imports:[ExportPanel,DocumentLibrary],providers:[{provide:Api,useValue:api}]});await TestBed.compileComponents();
    workspace=TestBed.inject(WorkspaceStore);workspace.data.set({studies:[{id:'pdf',title:'Wynik',imagingStudyId:null},{id:'dicom',title:'Obrazy',imagingStudyId:'dicom'}]} as unknown as WorkspaceData);
  });
  it('excludes full image series and requires explicit confirmation before creating a package',async()=>{
    const fixture=TestBed.createComponent(ExportPanel);fixture.componentRef.setInput('studyIds',['pdf','dicom']);await fixture.whenStable();
    const component=fixture.componentInstance;expect(component.chosen).toEqual(['pdf']);component.create();expect(api.createExport).not.toHaveBeenCalled();
    component.confirmed=true;component.create();expect(api.createExport).toHaveBeenCalledWith(expect.objectContaining({studyIds:['pdf'],format:'PDF',includePlan:false,includeProgress:false,confirmed:true}));
  });
  it('ignores an export response after the patient changes the selection',async()=>{
    const pending=new Subject<ExportJob>();api.createExport.mockReturnValue(pending);
    const fixture=TestBed.createComponent(ExportPanel);fixture.componentRef.setInput('studyIds',['pdf']);await fixture.whenStable();
    const component=fixture.componentInstance;component.confirmed=true;component.create();component.reset();pending.next({id:'old',status:'READY',format:'PDF',expiresAt:'2026-12-01',errorMessage:null});expect(component.job()).toBeNull();
  });
  it('renders matching document text as text, without executing source markup',async()=>{
    api.searchDocuments.mockReturnValue(of({items:[{id:'source',title:'Wynik',snippet:'<img src=x onerror=alert(1)> hasło',typeName:'Dokument',hasExtractedText:true}],total:1,page:0,pageSize:25}));
    workspace.libraryFilters.update(f=>({...f,query:'hasło'}));const fixture=TestBed.createComponent(DocumentLibrary);await fixture.whenStable();fixture.componentInstance.load();await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.row img')).toBeNull();expect(fixture.nativeElement.querySelector('mark')?.textContent).toBe('hasło');
    expect(fixture.nativeElement.textContent).toContain('<img src=x onerror=alert(1)>');
  });
  it('does not restore stale search results after a newer response',async()=>{
    const fixture=TestBed.createComponent(DocumentLibrary);await fixture.whenStable();const component=fixture.componentInstance;
    const pending=new Subject<LibraryPage>();api.searchDocuments.mockReturnValueOnce(pending).mockReturnValueOnce(of({items:[],total:0,page:0,pageSize:25}));
    component.load();component.load();pending.next({items:[],total:99,page:0,pageSize:25});expect(component.results()?.total).toBe(0);
  });
  it('preserves filters when the library is recreated after visiting a document',async()=>{
    const fixture=TestBed.createComponent(DocumentLibrary);await fixture.whenStable();fixture.componentInstance.change({query:'kontrola',from:'2026-01-01',text:'yes'});fixture.destroy();
    const next=TestBed.createComponent(DocumentLibrary);await next.whenStable();expect(next.componentInstance.filters()).toMatchObject({query:'kontrola',from:'2026-01-01',text:'yes'});
  });
});

import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, Subject } from 'rxjs';
import { KnowledgePanel } from './knowledge-panel';
import { Api } from '../../core/services/api';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { KnowledgeView, KnowledgeReply } from '../../core/models/knowledge';
const ready:KnowledgeView={id:'k',status:'READY',updatedAt:'2026-11-16T12:00:00Z',error:null,sources:[{id:'s',kind:'DOCUMENT',targetId:'doc',title:'Wynik',version:'v',limitation:''}],pages:[{id:'overview',title:'Najważniejsze informacje',points:[{kind:'FACT',sourceId:'s',quote:'Nie stwierdzono zmian.'}]}]};
describe('Patient knowledge',()=>{
 let api:any,workspace:any;
 beforeEach(()=>{api={knowledgeConfig:vi.fn(()=>of({enabled:true})),knowledge:vi.fn(()=>of(ready)),askKnowledge:vi.fn(()=>of({points:[],message:'Brak informacji',model:'test',contextLimited:false})),rebuildKnowledge:vi.fn(()=>of(ready))};workspace={role:signal('patient'),realtimeRevision:signal(0),visitFocusId:signal(null),selectView:vi.fn()};TestBed.configureTestingModule({imports:[KnowledgePanel],providers:[{provide:Api,useValue:api},{provide:WorkspaceStore,useValue:workspace}]});});
 it('shows source coverage and renders untrusted content as text',async()=>{const f=TestBed.createComponent(KnowledgePanel);await f.whenStable();f.componentInstance.toggle();await f.whenStable();expect(f.nativeElement.textContent).toContain('Nie stwierdzono zmian.');expect(f.nativeElement.querySelector('textarea').getAttribute('maxlength')).toBe('1000');f.destroy();});
 it('drops pending answers and clears old content on role change',async()=>{const pending=new Subject<KnowledgeReply>();api.askKnowledge.mockReturnValue(pending);const f=TestBed.createComponent(KnowledgePanel);await f.whenStable();f.componentInstance.toggle();f.componentInstance.question='Co zapisano?';f.componentInstance.ask();workspace.role.set('doctor');await f.whenStable();pending.next({points:[],message:'OLD',model:'test',contextLimited:false});expect(f.componentInstance.reply()).toBeNull();f.destroy();});
 it('clears cached evidence after a permission error',async()=>{const f=TestBed.createComponent(KnowledgePanel);await f.whenStable();f.componentInstance.toggle();const response=new Subject<KnowledgeView>();api.knowledge.mockReturnValue(response);f.componentInstance.load();response.error({error:{message:'Brak dostępu'}});expect(f.componentInstance.view()).toBeNull();expect(f.componentInstance.error()).toBe('Brak dostępu');f.destroy();});
});

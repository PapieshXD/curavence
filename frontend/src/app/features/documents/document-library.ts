import { KnowledgePanel } from './knowledge-panel';
import { Component, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { Api } from '../../core/services/api';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { LibraryItem, LibraryPage, RestorePreview } from '../../core/models/readiness';
import { ExportPanel } from './export-panel';

@Component({selector:'app-document-library',imports:[FormsModule,DatePipe,ExportPanel,KnowledgePanel],styleUrl:'./readiness.scss',template:`
  <section class="patient-surface">
    <header class="patient-page-heading"><div><h1>{{ workspace.role()==='patient'?'Badania':'Dokumenty i badania' }}</h1><p>Znajdź dokument po nazwie lub odczytanej treści.</p></div>
      @if (workspace.role()==='patient') { <button class="primary-button" type="button" (click)="upload()">Dodaj badanie</button><button class="secondary-button" type="button" (click)="workspace.patientResultsMode.set(true)">Zobacz wyniki badań</button> }
    </header>
    @if(workspace.role()==='patient'){<app-knowledge-panel />}
    <nav class="filters" aria-label="Filtry badań">
      @for (c of categories; track c.id) { <button class="secondary-button" type="button" [class.active]="filters().category===c.id" [attr.aria-pressed]="filters().category===c.id" (click)="change({category:c.id})">{{ c.label }}</button> }
    </nav>
    <label>Szukaj w nazwie lub treści<input type="search" [ngModel]="filters().query" (ngModelChange)="change({query:$event})" placeholder="Np. nazwa badania lub fragment opisu" /></label>
    <div class="controls">
      <label>Data badania od<input type="date" [ngModel]="filters().from" (ngModelChange)="change({from:$event})" /></label>
      <label>Data badania do<input type="date" [ngModel]="filters().to" (ngModelChange)="change({to:$event})" /></label>
      <label>Odczytana treść<select [ngModel]="filters().text" (ngModelChange)="change({text:$event})"><option value="all">Wszystkie</option><option value="yes">Z odczytanym tekstem</option><option value="no">Bez odczytanego tekstu</option></select></label>
      @if (workspace.role()==='patient') { <label>Widok<select [ngModel]="filters().archived" (ngModelChange)="change({archived:$event})"><option [ngValue]="false">Aktywne badania</option><option [ngValue]="true">Zarchiwizowane</option></select></label> }
    </div>
    <button class="secondary-button" type="button" (click)="clear()">Wyczyść filtry</button>
    @if (workspace.role()==='patient' && !filters().archived) { <app-export-panel [studyIds]="selected()" /> }
    @if (loading()) { <p role="status">Wyszukiwanie…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p><button type="button" class="secondary-button" (click)="load()">Spróbuj ponownie</button> }
    <p role="status">{{ results()?.total ?? 0 }} pozycji</p>
    @for (item of results()?.items ?? []; track item.id) {
      <article class="row">
        @if (workspace.role()==='patient' && !filters().archived && !item.imagingStudyId) { <label class="check"><input type="checkbox" [checked]="selected().includes(item.id)" (change)="select(item.id,$any($event.target).checked)" />Wybierz do pakietu: {{ item.title }}</label> }
        <h2>{{ item.title }}</h2><p>{{ item.performedAt ? (item.performedAt | date:'dd.MM.yyyy') : 'Brak daty badania' }} · {{ item.typeName }} · {{ item.sourceName || 'Dodano samodzielnie' }}</p>
        @if (item.snippet) { <p>@for(part of highlight(item.snippet);track $index){@if(part.match){<mark>{{part.text}}</mark>}@else{<span>{{part.text}}</span>}}</p> }
        @if (!item.hasExtractedText) { <p>{{ item.indexStatus==='PENDING' ? 'Treść jest przygotowywana do wyszukiwania. Dokument znajdziesz już po nazwie.' : item.indexStatus==='FAILED' ? 'Nie udało się przygotować treści do wyszukiwania. System spróbuje ponownie. Oryginał pozostaje dostępny.' : 'Brak odczytanego tekstu w wyszukiwarce. Dokument można znaleźć po nazwie i metadanych.' }}</p> }
        @if (!filters().archived) { <button class="primary-button" type="button" (click)="open(item)">Otwórz badanie</button> }
        @else { <button class="secondary-button" type="button" (click)="preview(item)">Przywróć dokument</button> }
      </article>
    } @empty { @if (!loading() && !error()) { <p>Brak dokumentów pasujących do filtrów.</p> } }
    <div class="pagination"><button type="button" class="secondary-button" [disabled]="filters().page===0 || loading()" (click)="page(-1)">Poprzednia strona</button><span>Strona {{ filters().page+1 }}</span><button type="button" class="secondary-button" [disabled]="!results() || (filters().page+1)*25>=results()!.total || loading()" (click)="page(1)">Następna strona</button></div>
    @if (restoring(); as item) {
      <section class="readiness-panel" aria-label="Potwierdzenie przywrócenia">
        <h2>Przywróć: {{ item.title }}</h2><p>Oryginał zostanie przywrócony bez ponawiania analiz.</p>
        @if (restorePreview(); as p) {
          <p>Po przywróceniu dokument ponownie obejmą poniższe aktywne zgody:</p>
          @for (r of p.readers; track r.grantId) { <p>{{ r.name }} · {{ r.email }} · {{ r.scope }}</p> } @empty { <p>Brak aktywnych zgód obejmujących ten dokument.</p> }
          <label class="check"><input type="checkbox" [(ngModel)]="restoreConfirmed" />Potwierdzam przywrócenie i pokazany zakres widoczności.</label>
          <button class="primary-button" type="button" [disabled]="restoreBusy() || !restoreConfirmed" (click)="restore()">Potwierdź przywrócenie</button>
        } @else { <p role="status">Sprawdzanie zakresu dostępu…</p> }
        <button class="secondary-button" type="button" (click)="restoring.set(null)">Anuluj</button>
      </section>
    }
  </section>
`})
export class DocumentLibrary {
  readonly workspace=inject(WorkspaceStore);private readonly api=inject(Api);private readonly router=inject(Router,{optional:true});private readonly destroy=inject(DestroyRef);
  readonly filters=this.workspace.libraryFilters;readonly results=signal<LibraryPage|null>(null);readonly loading=signal(false);readonly error=signal('');readonly selected=signal<string[]>([]);
  readonly restoring=signal<LibraryItem|null>(null);readonly restorePreview=signal<RestorePreview|null>(null);readonly restoreBusy=signal(false);restoreConfirmed=false;
  readonly categories=[{id:'all',label:'Wszystkie'},{id:'laboratory',label:'Laboratoryjne'},{id:'imaging',label:'Obrazowe'},{id:'ecg',label:'EKG / EEG'},{id:'documents',label:'Dokumenty'}];
  private request=0;private pending?:ReturnType<typeof setTimeout>;private search?:Subscription;private previewRequest=0;
  constructor(){effect(onCleanup=>{this.filters();this.workspace.role();this.workspace.realtimeRevision();this.workspace.search();this.results.set(null);this.loading.set(true);const revision=++this.request;
    this.pending=setTimeout(()=>{if(revision===this.request)untracked(()=>this.load());},250);onCleanup(()=>{clearTimeout(this.pending);this.search?.unsubscribe();});
  });this.destroy.onDestroy(()=>{clearTimeout(this.pending);this.search?.unsubscribe();});}
  change(patch:Partial<ReturnType<typeof this.filters>>){this.filters.update(f=>({...f,...patch,page:0}));if(patch.query!==undefined)this.workspace.search.set('');this.restoring.set(null);}
  page(delta:number){this.filters.update(f=>({...f,page:f.page+delta}));}
  clear(){this.workspace.search.set('');this.filters.set({query:'',category:'all',from:'',to:'',text:'all',page:0,archived:false});}
  load(){const request=++this.request,role=this.workspace.role(),f=this.filters();this.loading.set(true);this.error.set('');
    const params:Record<string,string|number|boolean>={query:this.workspace.search()||f.query,category:f.category,text:f.text,page:f.page,pageSize:25,archived:role==='patient'&&f.archived};if(f.from)params['from']=f.from;if(f.to)params['to']=f.to;
    this.search=this.api.searchDocuments(role,params).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:page=>{if(request!==this.request)return;this.results.set(page);this.loading.set(false);},error:e=>{if(request!==this.request)return;this.results.set(null);this.loading.set(false);this.error.set(e?.error?.message||'Nie udało się wyszukać dokumentów.');}});
  }
  select(id:string,checked:boolean){this.selected.update(ids=>checked?[...new Set([...ids,id])]:ids.filter(s=>s!==id));}
  highlight(text:string){const q=(this.workspace.search()||this.filters().query).trim();if(!q)return[{text,match:false}];const lower=text.toLocaleLowerCase(),needle=q.toLocaleLowerCase();const parts:{text:string;match:boolean}[]=[];let start=0,index=lower.indexOf(needle);while(index>=0){parts.push({text:text.slice(start,index),match:false},{text:text.slice(index,index+q.length),match:true});start=index+q.length;index=lower.indexOf(needle,start);}parts.push({text:text.slice(start),match:false});return parts;}
  open(item:LibraryItem){this.workspace.studyReturnView.set('results');void this.router?.navigate(item.imagingStudyId?['/imaging-studies',item.imagingStudyId,'viewer']:['/documents',item.id]);}
  upload(){void this.router?.navigateByUrl('/documents/upload');}
  preview(item:LibraryItem){const request=++this.previewRequest;this.restoring.set(item);this.restorePreview.set(null);this.restoreConfirmed=false;this.api.restorePreview(item.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:p=>{if(request===this.previewRequest && this.restoring()?.id===item.id)this.restorePreview.set(p);},error:e=>{this.restoring.set(null);this.error.set(e?.error?.message||'Nie udało się sprawdzić dostępu.');}});}
  restore(){const item=this.restoring(),p=this.restorePreview();if(!item||!p||!this.restoreConfirmed)return;this.restoreBusy.set(true);this.api.restoreDocument(item.id,{revision:p.revision,grantIds:p.readers.map(r=>r.grantId),confirmed:true}).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:()=>{this.restoreBusy.set(false);this.restoring.set(null);this.load();this.workspace.load(true);},error:e=>{this.restoreBusy.set(false);this.error.set(e?.error?.message||'Nie udało się przywrócić dokumentu.');this.preview(item);}});}
}

import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, timer } from 'rxjs';
import { Api } from '../../core/services/api';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { KnowledgeView, KnowledgeReply, KnowledgeSource } from '../../core/models/knowledge';
@Component({selector:'app-knowledge-panel',imports:[FormsModule,DatePipe],styleUrl:'./knowledge-panel.scss',template:`
@if (enabled()) {
<section class="knowledge-panel" aria-label="Podsumowanie dokumentacji">
  <div class="heading"><div><span class="eyebrow">DOKUMENTACJA W JEDNYM MIEJSCU</span><h2>Podsumowanie dokumentacji</h2>
    <p>{{ visitId() ? 'Dokumenty wybrane do tej wizyty i dostępne zatwierdzone plany.' : 'Twoje dokumenty i zatwierdzone plany wizyt, z odnośnikami do źródeł.' }}</p></div>
    <button type="button" class="secondary-button" [attr.aria-expanded]="opened()" (click)="toggle()">{{ opened()?'Zwiń podsumowanie':'Otwórz podsumowanie' }}</button>
  </div>
  @if (opened()) {
    <p class="notice">Opracowanie AI · Fragmenty źródeł pomagają odnaleźć informacje. Decyzje medyczne i zatwierdzone zalecenia pozostają w dokumentacji lekarza.</p>
    @if (loading()) { <p role="status">Sprawdzanie dokumentacji…</p> }
    @if (error()) { <p role="alert" class="error">{{ error() }}</p><button class="secondary-button" type="button" (click)="load()">Spróbuj ponownie</button> }
    @if (view(); as v) {
      <div class="toolbar"><p role="status">{{ status(v.status) }} · {{ v.updatedAt | date:'dd.MM.yyyy HH:mm' }}</p>
        <button class="secondary-button" type="button" [disabled]="busy()||v.status==='GENERATING'||v.status==='QUEUED'" (click)="rebuild()">Przygotuj ponownie</button></div>
      @if(v.error){<p class="notice">{{v.error}}</p>}
      @if(!v.sources.length){<p>Nie ma jeszcze dokumentów ani zatwierdzonych planów. Dodaj dokument w Badaniach lub wróć po zatwierdzeniu planu wizyty.</p>}
      @if(v.sources.length){
        <details><summary>Zakres opracowania · {{ v.sources.length }} źródeł</summary>
          @for(s of v.sources;track s.id){<div class="source"><strong>{{s.title}}</strong>@if(s.limitation){<p>{{s.limitation}}</p>}<button type="button" class="source-link" (click)="openSource(s)">{{s.kind==='VISIT'?'Otwórz plan wizyty':'Otwórz dokument źródłowy'}}</button></div>}
        </details>
        @for(page of v.pages;track page.id){
          <details [open]="page.id==='overview'"><summary>{{page.title}}</summary>
            @for(point of page.points;track $index){<article class="point"><span class="tag">{{kind(point.kind)}}</span><blockquote>{{point.quote}}</blockquote>
              @if(source(point.sourceId);as s){<button class="source-link" type="button" (click)="openSource(s)">Źródło: {{s.title}}</button>}
            </article>} @empty {<p>Nie wybrano potwierdzonych fragmentów. Otwórz źródła, aby przeczytać całość.</p>}
          </details>
        }
        <form (ngSubmit)="ask()"><label [for]="fieldId">Zapytaj o dostępną dokumentację</label>
          <textarea [id]="fieldId" name="knowledgeQuestion" [(ngModel)]="question" maxlength="1000" rows="3" placeholder="Np. jakie zalecenia zapisano w dokumentacji?"></textarea>
          <button class="primary-button" [disabled]="busy()||!question.trim()">{{busy()?'Sprawdzanie źródeł…':'Zapytaj'}}</button>
        </form>
        @if(reply();as answer){<section class="answer" aria-live="polite"><h3>Odpowiedź na podstawie źródeł</h3><p>{{answer.message}}</p>
          @if(answer.contextLimited){<p class="notice">Nie przeanalizowano całej treści. Sprawdź ograniczenia w zakresie opracowania.</p>}
          @for(point of answer.points;track $index){<blockquote>{{point.quote}}</blockquote>@if(source(point.sourceId);as s){<button class="source-link" type="button" (click)="openSource(s)">Źródło: {{s.title}}</button>}}
          @if(!answer.points.length){<p>Nie znaleziono potwierdzonej odpowiedzi w analizowanych fragmentach.</p>}
        </section>}
      }
    }
  }
</section>}
`})
export class KnowledgePanel {
  readonly workspace=inject(WorkspaceStore);private readonly api=inject(Api);private readonly router=inject(Router,{optional:true});private readonly destroy=inject(DestroyRef);
  readonly visitId=input<string|null>(null);readonly enabled=signal(false);readonly opened=signal(false);readonly loading=signal(false);readonly busy=signal(false);readonly error=signal('');readonly view=signal<KnowledgeView|null>(null);readonly reply=signal<KnowledgeReply|null>(null);
  private static sequence=0;readonly fieldId='knowledge-question-'+(++KnowledgePanel.sequence);question='';private generation=0;private request?:Subscription;private poll?:Subscription;
  constructor(){
    this.api.knowledgeConfig().pipe(takeUntilDestroyed(this.destroy)).subscribe({next:c=>this.enabled.set(c.enabled),error:()=>this.enabled.set(false)});
    effect(()=>{this.workspace.role();this.visitId();this.workspace.realtimeRevision();untracked(()=>{this.generation++;this.request?.unsubscribe();this.poll?.unsubscribe();this.view.set(null);this.reply.set(null);this.error.set('');this.busy.set(false);this.loading.set(false);if(this.opened())this.load();});});
    this.destroy.onDestroy(()=>{this.generation++;this.request?.unsubscribe();this.poll?.unsubscribe();});
  }
  toggle(){this.opened.update(x=>!x);if(this.opened())this.load();else{this.generation++;this.request?.unsubscribe();this.poll?.unsubscribe();this.busy.set(false);this.loading.set(false);this.reply.set(null);}}
  load(){const generation=++this.generation;this.request?.unsubscribe();this.poll?.unsubscribe();this.loading.set(true);this.error.set('');
    this.request=this.api.knowledge(this.workspace.role(),this.visitId()).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:v=>{if(generation!==this.generation)return;if(this.view()?.updatedAt!==v.updatedAt)this.reply.set(null);this.view.set(v);this.loading.set(false);this.poll=timer(v.status==='QUEUED'||v.status==='GENERATING'?5000:30000).subscribe(()=>this.load());},error:e=>{if(generation!==this.generation)return;this.view.set(null);this.reply.set(null);this.loading.set(false);this.error.set(e?.error?.message||'Nie udało się odczytać podsumowania.');}});
  }
  rebuild(){this.reply.set(null);this.api.rebuildKnowledge(this.workspace.role(),this.visitId()).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:()=>this.load(),error:e=>this.error.set(e?.error?.message||'Nie udało się rozpocząć aktualizacji.')});}
  ask(){if(this.busy()||!this.question.trim())return;const generation=++this.generation;this.request?.unsubscribe();this.busy.set(true);this.error.set('');this.reply.set(null);this.poll?.unsubscribe();
    this.request=this.api.askKnowledge(this.workspace.role(),this.visitId(),this.question.trim()).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:r=>{if(generation!==this.generation)return;this.reply.set(r);this.busy.set(false);this.poll=timer(30000).subscribe(()=>this.load());},error:e=>{if(generation!==this.generation)return;this.busy.set(false);this.reply.set(null);if([401,403,404].includes(e?.status))this.view.set(null);this.error.set(e?.error?.message||'Nie udało się potwierdzić odpowiedzi. Spróbuj ponownie.');}});
  }
  source(id:string){return this.view()?.sources.find(s=>s.id===id);}
  openSource(s:KnowledgeSource){if(s.kind==='VISIT'){this.workspace.visitFocusId.set(s.targetId);this.workspace.selectView('visits');}else void this.router?.navigate(['/documents',s.targetId]);}
  status(s:string){return ({QUEUED:'Oczekuje na przygotowanie',GENERATING:'Przygotowywanie',READY:'Aktualne',FAILED:'Nie udało się przygotować',INVALIDATED:'Zakres nie jest dostępny'} as Record<string,string>)[s]||s;}
  kind(s:string){return ({FACT:'Zapisano w źródle',RECOMMENDATION:'Zalecenie autora',DECLARATION:'Deklaracja pacjenta',DISCREPANCY:'Możliwa rozbieżność'} as Record<string,string>)[s]||'Fragment źródła';}
}

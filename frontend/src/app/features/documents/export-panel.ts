import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';
import { Api } from '../../core/services/api';
import { Readiness } from '../../core/services/readiness';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { Visit } from '../../core/models/visit';
import { ExportJob } from '../../core/models/readiness';

@Component({selector:'app-export-panel',imports:[FormsModule,DatePipe],styleUrl:'./readiness.scss',template:`
  @if (workspace.role()==='patient' && readiness.config().exportsEnabled) {
    <section class="readiness-panel">
      <button type="button" class="secondary-button" (click)="toggle()" [attr.aria-expanded]="opened()">{{ visit() ? 'Przygotuj pakiet do pobrania' : 'Pobierz wybrane' }}</button>
      @if (opened()) {
        <h3>Pakiet na wizytę poza Curavence</h3>
        @if (!job()) {
          <p>Sprawdź dokładny zakres. Domyślnie pobierzesz czytelny skrót PDF.</p>
          @if (visit(); as v) {
            <div class="source-preview"><strong>{{ v.reason }}</strong><p>Opis pacjenta: {{ v.symptoms || 'Nie dodano' }}</p><p>Pytania pacjenta: {{ v.questions || 'Nie dodano' }}</p></div>
            <label class="check"><input [disabled]="busy()" type="checkbox" [(ngModel)]="includePlan" (ngModelChange)="changed()" />Dołącz zatwierdzony plan lekarza</label>
            @if (includePlan) {
              <p class="source-preview">{{ v.approvedAt ? v.summary : 'Ta wizyta nie ma jeszcze zatwierdzonego planu. Robocze ustalenia lekarza nie trafią do pakietu.' }}</p>
              @if (v.previousPlan) { <label class="check"><input [disabled]="busy()" type="checkbox" [(ngModel)]="includePrevious" (ngModelChange)="changed()" />Dołącz również poprzedni zatwierdzony plan</label>
                @if (includePrevious) { <p class="source-preview">{{ v.previousPlan.summary }}</p> }
              }
              <label class="check"><input [disabled]="busy()" type="checkbox" [(ngModel)]="includeProgress" (ngModelChange)="changed()" />Dołącz zgłoszenia realizacji i oznaczenia omówienia</label>
              @for (a of previewActions(); track a.id) {
                <p>{{a.description}} · do {{a.dueDate | date:'dd.MM.yyyy'}}
                  @if(includeProgress){ · {{a.patientStatus==='DONE'?'Wykonane według pacjenta':a.patientStatus==='DIFFICULTY'?'Mam trudność':'Do wykonania'}} · {{a.discussedAt?'Omówione':'Do omówienia'}} }
                </p>
                @if(includeProgress){
                  <p class="source-preview">Notatka pacjenta: {{a.patientNote || 'Brak'}} · {{a.reportedName || 'Brak autora zgłoszenia'}} · {{a.reportedAt | date:'dd.MM.yyyy HH:mm'}}
                    @if(a.performedOn){ · wykonano według pacjenta: {{a.performedOn | date:'dd.MM.yyyy'}} }
                    @if(a.discussedAt){ · omówienie: {{a.discussedName}} · {{a.discussedAt | date:'dd.MM.yyyy HH:mm'}} }
                  </p>
                }
              }
            }
          }
          <fieldset><legend>Wybrane dokumenty ({{ chosen.length }}/50)</legend>
            @for (s of available(); track s.id) { <label class="check"><input [disabled]="busy()" type="checkbox" [checked]="chosen.includes(s.id)" (change)="select(s.id,$any($event.target).checked)" />{{ s.title }} · {{ s.performedAt | date:'dd.MM.yyyy' }}</label> }
            @if (!available().length) { <p>Brak dokumentów możliwych do dołączenia.</p> }
          </fieldset>
          <p>Pełne serie DICOM/NIfTI pobierz osobno z badania obrazowego. Do pakietu możesz dołączyć ich opisy PDF.</p>
          <label>Format<select [disabled]="busy()" [(ngModel)]="format" (ngModelChange)="changed()"><option value="PDF">Skrót PDF</option><option value="ZIP">Skrót PDF i wybrane oryginały (ZIP, do 250 MB)</option></select></label>
          <p>Przekazanej kopii nie można później wycofać przez cofnięcie zgody w Curavence.</p>
          <label class="check"><input [disabled]="busy()" type="checkbox" [(ngModel)]="confirmed" />Potwierdzam wybrany zakres i przygotowanie kopii.</label>
          <button class="primary-button" type="button" [disabled]="busy() || !confirmed || chosen.length>50 || (!visit() && !chosen.length)" (click)="create()">Przygotuj pakiet</button>
        } @else {
          <p role="status">{{ statuses[job()!.status] || job()!.status }}</p>
          @if (job()?.status==='READY') { <p>Do pobrania do {{ job()?.expiresAt | date:'dd.MM.yyyy HH:mm' }}. Zmiana źródeł wymaga przygotowania nowego pakietu.</p><button class="primary-button" type="button" [disabled]="busy()" (click)="download()">Pobierz pakiet {{ job()?.format }}</button> }
          @if (job()?.errorMessage) { <p>{{ job()?.errorMessage }}</p> }
          <button class="secondary-button" type="button" (click)="reset()">Przygotuj nowy pakiet</button>
        }
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      }
    </section>
  }
`})
export class ExportPanel {
  readonly workspace=inject(WorkspaceStore);readonly readiness=inject(Readiness);private readonly api=inject(Api);private readonly destroy=inject(DestroyRef);
  readonly visit=input<Visit|null>(null);readonly studyIds=input<string[]>([]);
  readonly opened=signal(false);readonly job=signal<ExportJob|null>(null);readonly busy=signal(false);readonly error=signal('');
  private polling?:Subscription;private generation=0;private requestKey=crypto.randomUUID();private sourceKey='';
  chosen:string[]=[];includePlan=false;includeProgress=false;includePrevious=false;format:'PDF'|'ZIP'='PDF';confirmed=false;
  readonly statuses:Record<string,string>={QUEUED:'Pakiet oczekuje na przygotowanie.',GENERATING:'Przygotowujemy pakiet…',READY:'Pakiet jest gotowy.',FAILED:'Nie udało się przygotować pakietu.',EXPIRED:'Pakiet wygasł.',INVALIDATED:'Źródła pakietu zmieniły się.'};
  constructor(){effect(()=>{const visit=this.visit()?.id;const ids=this.studyIds();const key=JSON.stringify([visit,ids]);if(key===this.sourceKey)return;this.sourceKey=key;this.chosen=ids.filter(id=>(this.workspace.data()?.studies??[]).some(s=>s.id===id && !s.imagingStudyId));this.reset();this.opened.set(false);});this.destroy.onDestroy(()=>this.polling?.unsubscribe());}
  previewActions(){const v=this.visit();return [...(v?.actions??[]),...(this.includePrevious?v?.previousPlan?.actions??[]:[])];}
  available(){return (this.workspace.data()?.studies??[]).filter(s=>!s.imagingStudyId);}
  toggle(){this.opened.update(v=>!v);}
  select(id:string,checked:boolean){this.chosen=checked?[...new Set([...this.chosen,id])]:this.chosen.filter(s=>s!==id);this.changed();}
  changed(){this.confirmed=false;this.requestKey=crypto.randomUUID();}
  reset(){this.generation++;this.polling?.unsubscribe();this.job.set(null);this.busy.set(false);this.error.set('');this.confirmed=false;this.requestKey=crypto.randomUUID();}
  create(){
    if(this.busy() || !this.confirmed)return;const generation=++this.generation;this.busy.set(true);this.error.set('');
    this.api.createExport({requestKey:this.requestKey,visitId:this.visit()?.id??null,studyIds:this.chosen,includePlan:this.includePlan,includeProgress:this.includePlan&&this.includeProgress,includePreviousPlan:this.includePlan&&this.includePrevious,format:this.format,confirmed:true})
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({next:job=>{if(generation!==this.generation)return;this.busy.set(false);this.job.set(job);this.poll(job.id,generation);},error:e=>{if(generation!==this.generation)return;this.busy.set(false);this.error.set(e?.error?.message||'Nie udało się zlecić pakietu. Spróbuj ponownie.');}});
  }
  private poll(id:string,generation:number){this.polling=timer(0,2000).pipe(switchMap(()=>this.api.exportStatus(id)),takeWhile(job=>['QUEUED','GENERATING'].includes(job.status),true),takeUntilDestroyed(this.destroy)).subscribe({next:job=>{if(generation===this.generation)this.job.set(job);},error:()=>{if(generation===this.generation)this.error.set('Nie udało się sprawdzić stanu pakietu. Otwórz ponownie wybór i przygotuj nowy pakiet.');}});}
  download(){const job=this.job();if(!job)return;this.busy.set(true);this.api.downloadExport(job.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:blob=>{this.busy.set(false);const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download='curavence-pakiet.'+job.format.toLowerCase();link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);},error:()=>{this.busy.set(false);this.error.set('Nie udało się pobrać pakietu. Źródła mogły się zmienić lub pakiet wygasł. Przygotuj go ponownie.');}});}
}

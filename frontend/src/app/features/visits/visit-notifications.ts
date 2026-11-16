import { Router } from '@angular/router';
import { Component, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Api } from '../../core/services/api';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { VisitNotification } from '../../core/models/visit';

@Component({
  selector: 'app-visit-notifications', imports: [DatePipe],
  styles: [`:host{position:relative} button{font:inherit;cursor:pointer;padding:10px;border:1px solid #b8cecb;border-radius:8px;background:white;color:#203d40} .notifications{position:absolute;right:0;top:48px;z-index:50;width:min(380px,85vw);max-height:70vh;overflow:auto;padding:20px;background:white;border:1px solid #b8cecb;border-radius:12px;box-shadow:0 12px 35px #203d4026} article{padding:14px 0;border-bottom:1px solid #dbe5e4} p{line-height:1.5} :focus-visible{outline:3px solid #267f77;outline-offset:3px}`],
  template: `
    @if (workspace.role() === 'patient' || workspace.role() === 'doctor') {
      <button #trigger type="button" [attr.aria-expanded]="opened()" aria-controls="visit-notifications" (click)="toggle()">Powiadomienia ({{ unread() }})</button>
      @if (opened()) {
        <section class="notifications" id="visit-notifications" aria-label="Powiadomienia" (keydown.escape)="opened.set(false); trigger.focus()">
          <button type="button" (click)="opened.set(false); trigger.focus()">Zamknij</button><h2>Powiadomienia</h2>
          @if (loading()) { <p role="status">Ładowanie…</p> }
          @if (error()) { <p role="alert">{{ error() }}</p><button type="button" (click)="load()">Spróbuj ponownie</button> }
          @for (n of items(); track n.id) {
            <article><strong>{{ labels[n.kind] || 'Zmiana w wizycie' }}</strong><p>{{ n.createdAt | date:'dd.MM.yyyy HH:mm' }} · {{ n.readAt ? 'Przeczytane' : 'Nowe' }}</p>
              <button type="button" (click)="open(n)">Otwórz wizytę</button>
              @if (!n.readAt) { <button type="button" (click)="read(n)">Oznacz jako przeczytane</button> }
            </article>
          } @empty { @if (!loading() && !error()) { <p>Brak powiadomień.</p> } }
          <p>Zgłoszenie trudności nie gwarantuje bieżącej odpowiedzi lekarza.</p>
        </section>
      }
    }
  `,
})
export class VisitNotifications {
  readonly workspace=inject(WorkspaceStore); private readonly api=inject(Api); private readonly destroy=inject(DestroyRef);
  readonly items=signal<VisitNotification[]>([]); readonly opened=signal(false); readonly loading=signal(false); readonly error=signal('');
  private generation=0; private readonly router=inject(Router, {optional:true});
  readonly labels: Record<string,string>={PLAN_APPROVED:'Lekarz zatwierdził plan',DUE:'Zbliża się termin kroku z planu',PROGRESS:'Pacjent zaktualizował realizację',OBSTACLE:'Pacjent zgłosił trudność'};
  constructor() {
    effect(()=>{this.workspace.role(); untracked(()=>{this.opened.set(false);this.items.set([]);this.load();});});
    effect(()=>{this.workspace.realtimeRevision(); const ready=!!this.workspace.data(); untracked(()=>{ if(!ready){++this.generation;this.items.set([]);this.opened.set(false);}else this.load(); });});
  }
  unread(): number { return this.items().filter(n=>!n.readAt).length; }
  toggle(): void {this.opened.update(v=>!v);if(this.opened()) this.load();}
  load(): void {
    const role=this.workspace.role(), generation=++this.generation;
    if(role!=='patient' && role!=='doctor') {this.items.set([]);return;}
    this.loading.set(true);this.error.set('');
    this.api.visitNotifications(role).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next:items=>{if(generation===this.generation){this.items.set(items);this.loading.set(false);}},
      error:()=>{if(generation===this.generation){this.items.set([]);this.loading.set(false);this.error.set('Nie udało się pobrać powiadomień.');}}
    });
  }
  read(n: VisitNotification): void {
    const role=this.workspace.role();
    this.api.readVisitNotification(role,n.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:()=>{if(role===this.workspace.role())this.load();},error:()=>this.error.set('Nie udało się oznaczyć powiadomienia.')});
  }
  open(n: VisitNotification): void {
    this.workspace.visitFocusId.set(n.visitId);
    if(this.router) void this.router.navigateByUrl('/').then(()=>this.workspace.selectView('visits'));
    else this.workspace.selectView('visits');
    this.opened.set(false);if(!n.readAt)this.read(n);
  }
}

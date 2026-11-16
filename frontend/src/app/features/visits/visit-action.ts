import { Component, input, output, effect } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { VisitAction, VisitProgress } from '../../core/models/visit';
import { StudyRecord } from '../../core/models/medical';

@Component({
  selector: 'app-visit-action', imports: [FormsModule, DatePipe, RouterLink],
  styleUrl: './visits.scss',
  template: `
    <article class="action">
      <h4>{{ action().description }}</h4>
      <p>{{ action().responsible }} · do {{ action().dueDate | date:'dd.MM.yyyy' }}</p>
      <p><strong>{{ labels[action().patientStatus] }}</strong> · {{ action().discussedAt ? 'Omówione przez lekarza' : 'Do omówienia' }}</p>
      @if (action().performedOn) { <p>Data wykonania według pacjenta: {{ action().performedOn | date:'dd.MM.yyyy' }}</p> }
      @if (action().patientNote) { <p class="pre">Notatka pacjenta: {{ action().patientNote }}</p> }
      @if (action().obstacle) { <p>Trudność: {{ obstacles[action().obstacle!] }}</p> }
      @if (action().reportedAt) { <small>{{ action().reportedName || 'Pacjent' }} zgłosił(a) zmianę: {{ action().reportedAt | date:'dd.MM.yyyy HH:mm' }}</small> }
      @else { <small>Brak daty zgłoszenia. Brak wpisu nie oznacza niewykonania zadania.</small> }
      @if (action().discussedAt) { <p>Omówienie — {{ action().discussedName || 'Lekarz' }}: {{ action().discussedAt | date:'dd.MM.yyyy HH:mm' }}</p> }
      @for (id of action().studyIds; track id) {
        @if (study(id); as s) {
          <a class="source" [routerLink]="s.imagingStudyId ? ['/imaging-studies',s.imagingStudyId] : ['/documents',s.id]">{{ s.title }} — otwórz badanie</a>
          @if (s.primaryAssetId) { <button type="button" class="secondary-button" (click)="original.emit(s)">Otwórz oryginał</button> }
        } @else { <p>Powiązany dokument jest niedostępny lub zarchiwizowany.</p> }
      }
      @if (patient()) {
        <details><summary>Zgłoś realizację lub trudność</summary>
          <form (ngSubmit)="save()">
            <label>Status realizacji<select name="status" [(ngModel)]="status"><option value="TODO">Do wykonania</option><option value="DONE">Wykonane według pacjenta</option><option value="DIFFICULTY">Mam trudność</option></select></label>
            @if (status === 'DONE') { <label>Data wykonania (opcjonalnie)<input name="performed" type="date" [(ngModel)]="performedOn" /></label> }
            @if (status === 'DIFFICULTY') {
              <label>Przyczyna trudności<select name="obstacle" [(ngModel)]="obstacle" required><option value="">Wybierz przyczynę</option><option value="NO_APPOINTMENT">Brak terminu</option><option value="COST">Koszt</option><option value="UNCLEAR">Niezrozumiałe zalecenie</option><option value="OTHER">Inna przyczyna</option></select></label>
            }
            <label>Notatka (opcjonalnie)<textarea name="note" [(ngModel)]="note" maxlength="2000" rows="3"></textarea></label>
            <fieldset><legend>Powiąż wyniki z zadaniem</legend>
              @for (s of studies(); track s.id) { <label class="check"><input type="checkbox" [checked]="studyIds.includes(s.id)" (change)="toggle(s.id,$any($event.target).checked)" />{{ s.title }}</label> }
              @for (id of studyIds; track id) { @if (!study(id)) { <label class="check"><input type="checkbox" checked (change)="toggle(id,false)" />Niedostępny dokument — usuń powiązanie</label> } }
            </fieldset>
            <p>Powiązanie wyniku nie udostępnia go lekarzowi. Zakres potwierdzisz osobno w pakiecie wizyty.</p>
            <p>Zgłoszenie trudności nie gwarantuje bieżącej odpowiedzi lekarza.</p>
            <button class="primary-button" [disabled]="busy() || (status === 'DIFFICULTY' && !obstacle)">Zapisz zgłoszenie</button>
          </form>
        </details>
      } @else if (canDiscuss()) {
        <button class="secondary-button" type="button" [disabled]="busy()" (click)="discussion.emit(!action().discussedAt)">{{ action().discussedAt ? 'Cofnij oznaczenie omówienia' : 'Oznacz jako omówione' }}</button>
      }
    </article>
  `,
})
export class VisitActionCard {
  readonly action = input.required<VisitAction>();
  readonly patient = input(false); readonly canDiscuss = input(false); readonly busy = input(false);
  readonly studies = input<StudyRecord[]>([]);
  readonly original = output<StudyRecord>();
  readonly progress = output<VisitProgress>(); readonly discussion = output<boolean>();
  readonly labels = {TODO:'Do wykonania',DONE:'Wykonane według pacjenta',DIFFICULTY:'Mam trudność'};
  readonly obstacles: Record<string,string> = {NO_APPOINTMENT:'Brak terminu',COST:'Koszt',UNCLEAR:'Niezrozumiałe zalecenie',OTHER:'Inna przyczyna'};
  private loadedKey='';
  status = 'TODO'; performedOn = ''; note = ''; obstacle = ''; studyIds: string[] = [];
  constructor() { effect(() => { const a=this.action();const key=`${a.id}:${a.revision}`;if(key===this.loadedKey)return;this.loadedKey=key; this.status=a.patientStatus; this.performedOn=a.performedOn ?? ''; this.note=a.patientNote; this.obstacle=a.obstacle ?? ''; this.studyIds=[...a.studyIds]; }); }
  study(id: string): StudyRecord | undefined { return this.studies().find(s => s.id===id); }
  toggle(id: string, checked: boolean): void { this.studyIds=checked ? [...new Set([...this.studyIds,id])] : this.studyIds.filter(s=>s!==id); }
  save(): void { this.progress.emit({status:this.status,performedOn:this.status==='DONE' ? this.performedOn || null : null,note:this.note,obstacle:this.status==='DIFFICULTY' ? this.obstacle : null,studyIds:this.studyIds,revision:this.action().revision}); }
}

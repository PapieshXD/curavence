import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, OnInit, computed, inject, signal, effect, untracked, DestroyRef } from '@angular/core';
import { Router } from '@angular/router';
import { StudyRecord } from '../../../../core/models/medical';
import { Visit } from '../../../../core/models/visit';
import { PersonalReminder } from '../../../../core/models/reading-guide';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { patientStudyStatus, patientStudyTitle } from '../../patient-study';

interface StudyMonth { label: string; studies: StudyRecord[] }

@Component({
  selector: 'app-patient-dashboard',
  imports: [AppIcon],
  templateUrl: './patient-dashboard.html',
  styleUrl: './patient-dashboard.scss',
})
export class PatientDashboard implements OnInit {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly api = inject(Api);
  private readonly router = inject(Router, { optional: true });
  protected readonly visits = signal<Visit[]>([]);
  protected readonly personalReminders = signal<PersonalReminder[]>([]);
  protected readonly reminderError = signal('');
  protected readonly nextReminder = computed<PersonalReminder | null>(() => [...this.personalReminders()].filter(r => !r.completed)
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate))[0] ?? null);
  protected readonly showReminderFirst = computed(() => {
    const reminder = this.nextReminder();
    return !!reminder && reminder.dueDate <= (this.nextAction()?.dueDate ?? '9999-12-31')
      && reminder.dueDate <= (this.nextVisit()?.visitDate ?? '9999-12-31');
  });
  protected readonly visibleCount = signal(8);
  protected readonly studies = computed(() => [...(this.workspace.data()?.studies ?? [])]
    .sort((left, right) => (right.performedAt ?? '').localeCompare(left.performedAt ?? '')));
  protected readonly latestStudy = computed(() => this.studies()[0] ?? null);
  protected readonly nextAction = computed(() => this.visits().flatMap(visit => visit.actions)
    .filter(action => !action.completed)
    .sort((left, right) => left.dueDate.localeCompare(right.dueDate))[0] ?? null);
  protected readonly nextVisit = computed(() => this.visits()
    .filter(visit => !!visit.visitDate && visit.visitDate >= this.today())
    .sort((left, right) => (left.visitDate ?? '').localeCompare(right.visitDate ?? ''))[0] ?? null);
  protected readonly nearestAction = computed(() => {
    const action = this.nextAction(), visit = this.nextVisit();
    return action && action.dueDate <= (visit?.visitDate ?? '9999-12-31') ? action : null;
  });
  protected readonly activeGrants = computed(() => (this.workspace.data()?.grants ?? [])
    .filter(grant => grant.status === 'ACTIVE' && (!grant.expiresAt || new Date(grant.expiresAt).getTime() > Date.now())));
  protected readonly months = computed<StudyMonth[]>(() => {
    const groups = new Map<string, StudyRecord[]>();
    for (const study of this.studies().slice(0, this.visibleCount())) {
      const key = study.performedAt?.slice(0, 7) || 'unknown';
      groups.set(key, [...(groups.get(key) ?? []), study]);
    }
    return Array.from(groups, ([key, studies]) => ({
      label: key === 'unknown' ? 'Bez daty' : this.monthLabel(`${key}-01`), studies,
    }));
  });
  protected readonly hasOlderEvents = computed(() => this.visibleCount() < this.studies().length);

  private visitRequest=0;
  private readonly destroy = inject(DestroyRef);
  constructor() { effect(() => { this.workspace.realtimeRevision(); untracked(() => {
    const request=++this.visitRequest;
    this.api.visits('patient').pipe(takeUntilDestroyed(this.destroy)).subscribe({next:v=>{if(request===this.visitRequest)this.visits.set(v);},error:()=>{if(request===this.visitRequest)this.visits.set([]);}});
  }); }); }
  ngOnInit(): void {
    this.api.personalReminders().subscribe({next: reminders => this.personalReminders.set(reminders),
      error: err => { if (err?.status !== 404) this.reminderError.set('Nie udało się sprawdzić osobistych przypomnień.'); }});
  }
  protected openReminder(): void {
    const reminder = this.nextReminder();
    if (reminder) { this.workspace.studyReturnView.set('overview'); void this.router?.navigate(['/documents', reminder.documentId]); }
  }

  protected openStudy(study: StudyRecord): void {
    this.workspace.studyReturnView.set('overview');
    void this.router?.navigate(study.imagingStudyId
      ? ['/imaging-studies', study.imagingStudyId, 'viewer']
      : ['/documents', study.id]);
  }

  protected selectView(view: string): void { this.workspace.selectView(view); }
  protected showOlderEvents(): void { this.visibleCount.update(count => count + 8); }
  protected title(study: StudyRecord): string { return patientStudyTitle(study); }
  protected status(study: StudyRecord): string { return patientStudyStatus(study); }
  protected date(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'long', year: 'numeric' }).format(new Date(value)) : 'Nie podano daty';
  }
  private today(): string { return new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Warsaw'}).format(new Date()); }
  private monthLabel(value: string): string {
    const label = new Intl.DateTimeFormat('pl-PL', { month: 'long', year: 'numeric' }).format(new Date(value));
    return label.charAt(0).toLocaleUpperCase('pl') + label.slice(1);
  }
}

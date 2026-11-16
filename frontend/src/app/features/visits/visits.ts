import { KnowledgePanel } from '../documents/knowledge-panel';
import { ExportPanel } from '../documents/export-panel';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { VisitActionCard } from './visit-action';
import { Component, computed, effect, inject, signal, untracked, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { Api } from '../../core/services/api';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { Visit, VisitAction, VisitProgress } from '../../core/models/visit';
import { StudyRecord } from '../../core/models/medical';

@Component({
  selector: 'app-visits', imports: [KnowledgePanel, ExportPanel, FormsModule, DatePipe, RouterLink, VisitActionCard],
  templateUrl: './visits.html', styleUrl: './visits.scss',
})
export class Visits {
  readonly workspace = inject(WorkspaceStore);
  private readonly api = inject(Api);
  private readonly destroyRef = inject(DestroyRef);
  private request = 0;
  private lastRole: string | null = null;
  readonly planDirty = signal(false);
  previousVisitId = ''; includePreviousPlan = false; scopeStudyIds: string[] = []; scopeConfirmed = false;
  readonly previousOptions = computed(() => this.visits().filter(v => !!v.approvedAt && v.id !== this.selected()?.id));
  readonly visits = signal<Visit[]>([]);
  readonly selected = signal<Visit | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly loading = signal(false);
  readonly editing = signal(false);
  readonly patient = computed(() => this.workspace.role() === 'patient');
  readonly canApprove = computed(() => this.workspace.role() === 'doctor' && !!this.workspace.data()?.session.roles.includes('DOCTOR'));
  readonly studies = computed(() => this.workspace.data()?.studies ?? []);
  readonly selectedStudies = computed(() => this.studies().filter(s => this.selected()?.studyIds.includes(s.id)));
  readonly overdue = computed(() => this.visits().flatMap(v => v.actions).filter(a => !a.completed && a.dueDate <= this.today()).length);
  reason = ''; symptoms = ''; questions = ''; visitDate = ''; studyIds: string[] = [];
  email = ''; expires = ''; confirmed = false; approveConfirmed = false;
  summary = ''; actions: {description: string; dueDate: string; responsible: string}[] = [];
  constructor() {
    effect(() => {
      const role=this.workspace.role();this.workspace.realtimeRevision();
      untracked(() => {
        const changed=this.lastRole!==role;this.lastRole=role;
        if(changed){this.selected.set(null);this.visits.set([]);this.editing.set(false);this.busy.set(false);}
        if(changed || (!this.editing() && !this.busy()))this.load();
      });
    });
    effect(() => { const focus=this.workspace.visitFocusId(); if(focus) untracked(()=>this.load()); });
    effect(() => {
      const draft = this.workspace.visitQuestionDraft();
      if (!draft || !this.patient()) return;
      this.edit(null);
      this.reason = 'Omówienie wyniku badania';
      this.questions = draft.question + (draft.sourceQuote ? '\n\nFragment wyniku: ' + draft.sourceQuote.trim() : '');
      this.studyIds = [draft.studyId];
      this.workspace.visitQuestionDraft.set(null);
    });
  }
  today(): string { return new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Warsaw'}).format(new Date()); }
  load(): void {
    const request = ++this.request; const role=this.workspace.role();
    this.loading.set(true); this.error.set('');
    this.api.visits(this.workspace.role()).pipe(takeUntilDestroyed(this.destroyRef), finalize(() => { if(request===this.request) this.loading.set(false); })).subscribe({
      next: visits => { if(request!==this.request || role!==this.workspace.role()) return; this.visits.set(visits); const id = this.workspace.visitFocusId() ?? this.selected()?.id; this.workspace.visitFocusId.set(null); if (id) {
        const updated=visits.find(v=>v.id===id) ?? null;
        if(updated && this.selected()?.id===id && this.planDirty()) this.selected.set(updated);
        else this.open(updated);
      } },
      error: () => { if(request!==this.request) return; this.visits.set([]); this.selected.set(null); this.error.set('Nie udało się pobrać wizyt. Spróbuj ponownie.'); },
    });
  }
  open(visit: Visit | null): void {
    this.planDirty.set(false); this.selected.set(visit); this.editing.set(false); this.confirmed = false; this.approveConfirmed = false;
    this.scopeStudyIds=[]; this.scopeConfirmed=false;
    this.email = ''; this.expires = ''; this.summary = visit?.summary ?? '';
    this.actions = visit?.actions.map(a => ({description:a.description, dueDate:a.dueDate, responsible:a.responsible})) ?? [];
  }
  edit(visit: Visit | null): void {
    this.open(visit); this.reason = visit?.reason ?? ''; this.symptoms = visit?.symptoms ?? '';
    this.questions = visit?.questions ?? ''; this.visitDate = visit?.visitDate ?? ''; this.studyIds = [...(visit?.studyIds ?? [])]; this.previousVisitId=visit?.previousVisitId ?? ''; this.includePreviousPlan=visit?.includePreviousPlan ?? false; this.editing.set(true);
  }
  toggleStudy(id: string, checked: boolean): void { this.studyIds = checked ? [...this.studyIds, id] : this.studyIds.filter(s => s !== id); }
  downloadStudy(study: StudyRecord): void {
    if (study.primaryAssetId) this.workspace.download(study.primaryAssetId, study.originalFilename || study.title);
  }
  save(): void {
    if (!this.reason.trim() || this.busy()) return;
    const role=this.workspace.role(); this.busy.set(true); this.error.set('');
    this.api.saveVisit(role, this.selected()?.id ?? null, {
      reason:this.reason, symptoms:this.symptoms, questions:this.questions, visitDate:this.visitDate || null, studyIds:this.studyIds, previousVisitId:this.previousVisitId || null, includePreviousPlan:!!this.previousVisitId && this.includePreviousPlan,
    }).pipe(takeUntilDestroyed(this.destroyRef),finalize(() => this.busy.set(false))).subscribe({
      next: result => { if(role!==this.workspace.role())return; this.editing.set(false);this.workspace.visitFocusId.set(result.id);this.load(); },
      error: e => {if(role===this.workspace.role())this.fail(e);},
    });
  }
  share(): void {
    const visit = this.selected(); if (!visit || !this.confirmed || !this.email || !this.expires || this.busy()) return;
    this.mutate('share', {recipientEmail:this.email, expiresAt:new Date(this.expires).toISOString(), confirmed:true});
  }
  savePlan(approved: boolean): void {
    if (!this.summary.trim() || (approved && !this.approveConfirmed)) return;
    this.mutate('plan', {summary:this.summary, actions:this.actions, approved});
  }
  progress(visitId: string, action: VisitAction, input: VisitProgress): void {
    this.updateAction(visitId, `actions/${action.id}/progress`, input);
  }
  discuss(action: VisitAction, discussed: boolean): void { this.mutate(`actions/${action.id}/discussion`, {discussed,revision:action.revision}); }
  private updateAction(visitId: string, action: string, body: unknown): void {
    if(this.busy()) return;
    const role=this.workspace.role(); this.busy.set(true); this.error.set('');
    this.api.updateVisit(role,visitId,action,body).pipe(takeUntilDestroyed(this.destroyRef),finalize(()=>this.busy.set(false))).subscribe({
      next:()=>{ if(role===this.workspace.role()) this.load(); }, error:e=>{ if(role===this.workspace.role()) this.fail(e); }
    });
  }
  extendScope(): void { if(this.scopeConfirmed) this.mutate('scope',{studyIds:this.scopeStudyIds,confirmed:true}); }
  toggleScope(id: string, checked: boolean): void { this.scopeConfirmed=false; this.scopeStudyIds=checked ? [...this.scopeStudyIds,id] : this.scopeStudyIds.filter(s=>s!==id); }

  private mutate(action: string, body: unknown): void {
    const visit = this.selected(); if (!visit || this.busy()) return;
    const role=this.workspace.role();this.busy.set(true); this.error.set('');
    this.api.updateVisit(role, visit.id, action, body).pipe(takeUntilDestroyed(this.destroyRef),finalize(() => this.busy.set(false))).subscribe({
      next: () => { if(role!==this.workspace.role())return; this.planDirty.set(false); this.load(); if (action === 'share' || action === 'scope') this.workspace.load(true); }, error: e => {if(role===this.workspace.role())this.fail(e);},
    });
  }
  revoke(): void {
    const id = this.selected()?.grantId; if (!id || this.busy()) return;
    this.busy.set(true); this.workspace.revokeGrant(id).pipe(finalize(() => this.busy.set(false))).subscribe({next: () => this.load(), error: e => this.fail(e)});
  }
  private fail(e: {status?: number; error?: {message?: string}}): void { if(e.status===409) { this.error.set('Dane zmieniły się. Odśwież widok przed ponownym zapisem.'); return; } this.error.set(e?.error?.message ?? 'Nie udało się zapisać zmian. Sprawdź dane i spróbuj ponownie.'); }
  status(v: Visit): string { return v.approvedAt ? 'Plan zatwierdzony' : ({DRAFT:'Szkic pakietu', ACTIVE:'Udostępniony', REVOKED:'Dostęp cofnięty', EXPIRED:'Dostęp wygasł'}[v.accessStatus] ?? v.accessStatus); }
  newSincePrevious(v: Visit, studyId: string): boolean {
    return v.newStudyIds?.includes(studyId) ?? false;
  }
}

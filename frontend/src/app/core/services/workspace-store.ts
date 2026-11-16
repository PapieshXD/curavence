import { Injectable, computed, inject, signal } from '@angular/core';
import { finalize, Observable, tap } from 'rxjs';
import { WorkspaceData } from '../models/medical';
import { WorkspaceConfig, WorkspaceRole } from '../models/workspace';
import { Api } from './api';

const SHARED_DOCUMENTS = { id: 'documents', label: 'Dokumenty', icon: 'document' };
const SHARED_IMAGING = { id: 'imaging', label: 'Badania obrazowe', icon: 'scan' };
const WORKSPACES: Record<WorkspaceRole, Omit<WorkspaceConfig, 'userName' | 'shortLabel'>> = {
  admin: {
    label: 'Administrator',
    title: 'Kontrola systemu',
    subtitle: 'Dostęp, bezpieczeństwo i ciągłość działania',
    navigation: [
      { id: 'overview', label: 'Przegląd', icon: 'overview' },
      { id: 'patients', label: 'Pacjenci', icon: 'users' },
      SHARED_DOCUMENTS,
      SHARED_IMAGING,
      { id: 'sharing', label: 'Udostępnianie', icon: 'share' },
      { id: 'audit', label: 'Dziennik audytu', icon: 'audit' },
    ],
  },
  patient: {
    label: 'Pacjent',
    title: 'Start',
    subtitle: 'Twoje badania i kolejne kroki',
    navigation: [
      { id: 'overview', label: 'Start', icon: 'overview' },
      { id: 'results', label: 'Badania', icon: 'lab' },
      { id: 'visits', label: 'Wizyty', icon: 'calendar' },
      { id: 'sharing', label: 'Udostępnianie', icon: 'share' },
      { id: 'profile', label: 'Konto', icon: 'settings' },
    ],
  },
  doctor: {
    label: 'Lekarz / diagnosta',
    title: 'Pracownia przypadku',
    subtitle: 'Kolejka, obrazowanie i decyzje w jednym kontekście',
    navigation: [
      { id: 'overview', label: 'Kolejka przypadków', icon: 'clipboard' },
      { id: 'visits', label: 'Wizyty i plan', icon: 'calendar' },
      { id: 'patients', label: 'Moi pacjenci', icon: 'users' },
      { id: 'sharing', label: 'Połącz z pacjentem', icon: 'share' },
      SHARED_DOCUMENTS,
      SHARED_IMAGING,
      { id: 'audit', label: 'Historia dostępu', icon: 'audit' },
    ],
  },
  therapy: {
    label: 'Fizjo / trener',
    title: 'Plan opieki',
    subtitle: 'Cele, ograniczenia i kolejne działania pacjenta',
    navigation: [
      { id: 'overview', label: 'Plan opieki', icon: 'clipboard' },
      { id: 'patients', label: 'Moi pacjenci', icon: 'users' },
      SHARED_DOCUMENTS,
      SHARED_IMAGING,
      { id: 'sharing', label: 'Zakres dostępu', icon: 'shield' },
      { id: 'audit', label: 'Historia dostępu', icon: 'audit' },
    ],
  },
};

@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(Api);

  readonly role = signal<WorkspaceRole>('patient');
  readonly activeView = signal('overview');
  readonly search = signal('');
  readonly selectedPatientId = signal('');
  readonly selectedStudyId = signal('');
  readonly studyReturnView = signal<'overview' | 'results'>('results');
  readonly patientResultsMode = signal(false);
  readonly visitQuestionDraft = signal<{ studyId: string; question: string; termId?: string; sourceQuote?: string } | null>(null);
  readonly mobileNavigationOpen = signal(false);
  readonly notification = signal('');
  readonly data = signal<WorkspaceData | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly notificationVisible = computed(() => this.notification().length > 0);
  readonly canSwitchViews = computed(() => this.data()?.session.superuser ?? false);
  readonly availableViews = computed<WorkspaceRole[]>(() =>
    this.data()?.session.availableViews ?? [this.role()],
  );
  readonly config = computed<WorkspaceConfig>(() => {
    const base = WORKSPACES[this.role()];
    const session = this.data()?.session;
    const userName = session?.displayName ?? 'Użytkownik Curavence';
    const shortLabel = session
      ? `${session.firstName.charAt(0)}${session.lastName.charAt(0)}`.toUpperCase()
      : 'CV';
    return { ...base, userName, shortLabel };
  });

  readonly libraryFilters = signal({query:'',category:'all',from:'',to:'',text:'all',page:0,archived:false});
  readonly visitFocusId = signal<string | null>(null);
  readonly realtimeRevision = signal(0);
  private requestGeneration = 0;

  invalidateAccess(): void {
    this.requestGeneration++;
    this.data.set(null);
    this.selectedStudyId.set('');
    this.selectedPatientId.set('');
    this.realtimeRevision.update(value => value + 1);
  }

  initialize(): void {
    if (!this.data() && !this.loading()) this.load();
  }

  reset(): void {
    this.requestGeneration++;
    this.role.set('patient');
    this.activeView.set('overview');
    this.search.set('');
    this.mobileNavigationOpen.set(false);
    this.notification.set('');
    this.data.set(null);
    this.selectedPatientId.set('');
    this.selectedStudyId.set('');
    this.studyReturnView.set('results');
    this.patientResultsMode.set(false);
    this.visitQuestionDraft.set(null);
    this.visitFocusId.set(null);
    this.libraryFilters.set({query:'',category:'all',from:'',to:'',text:'all',page:0,archived:false});
    this.loading.set(false);
    this.error.set('');
  }

  load(background = false): void {
    const generation = ++this.requestGeneration;
    if (!background) this.loading.set(true);
    this.error.set('');
    this.api.workspace(this.role()).pipe(finalize(() => { if (generation === this.requestGeneration) this.loading.set(false); })).subscribe({
      next: (data) => {
        if (generation !== this.requestGeneration) return;
        this.data.set(data);
        this.role.set(data.session.effectiveView);
      },
      error: () => { if (generation === this.requestGeneration) this.error.set('Nie udało się pobrać danych z backendu.'); },
    });
  }

  selectRole(role: WorkspaceRole): void {
    if (!this.availableViews().includes(role)) return;
    this.role.set(role);
    this.libraryFilters.set({query:'',category:'all',from:'',to:'',text:'all',page:0,archived:false});
    this.activeView.set('overview');
    this.search.set('');
    this.mobileNavigationOpen.set(false);
    this.notify(`Przełączono widok: ${WORKSPACES[role].label}`);
    this.load();
  }

  selectView(view: string): void {
    this.activeView.set(view);
    this.mobileNavigationOpen.set(false);
  }

  notify(message: string): void {
    this.notification.set(message);
    window.setTimeout(() => {
      if (this.notification() === message) this.notification.set('');
    }, 3200);
  }

  uploadStudy(file: File, title: string, studyTypeCode: string, patientId?: string): Observable<unknown> {
    const form = new FormData();
    form.append('file', file);
    form.append('title', title);
    form.append('studyTypeCode', studyTypeCode);
    if (patientId) form.append('patientId', patientId);
    return this.api.uploadStudy(this.role(), form).pipe(tap(() => this.load()));
  }

  createGrant(recipientEmail: string, scope: string, expiresAt: string | null, purpose = 'Konsultacja i ciągłość opieki'): Observable<unknown> {
    return this.api.createGrant(this.role(), {
      recipientEmail,
      scope,
      purpose,
      expiresAt,
      studyIds: [],
    }).pipe(tap(() => this.load()));
  }

  revokeGrant(id: string): Observable<void> {
    return this.api.revokeGrant(this.role(), id).pipe(tap(() => this.load()));
  }

  addReview(studyId: string, summary: string, conclusion: string, signed: boolean, summaryHtml?: string, conclusionHtml?: string): Observable<unknown> {
    return this.api.addReview(this.role(), studyId, { summary, conclusion, signed, summaryHtml, conclusionHtml })
      .pipe(tap(() => this.load()));
  }

  updatePhone(phone: string): Observable<void> {
    return this.api.updatePatient(this.role(), phone).pipe(tap(() => this.load()));
  }

  download(assetId: string, filename: string): void {
    this.api.downloadAsset(this.role(), assetId).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        link.click();
        URL.revokeObjectURL(url);
      },
      error: (error) => this.notify(error?.error?.message ?? 'Nie udało się pobrać pliku.'),
    });
  }
}


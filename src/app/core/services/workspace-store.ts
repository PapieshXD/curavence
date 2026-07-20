import { Injectable, computed, inject, signal } from '@angular/core';
import { finalize, Observable, tap } from 'rxjs';
import { WorkspaceData } from '../models/medical';
import { WorkspaceConfig, WorkspaceRole } from '../models/workspace';
import { Api } from './api';

const SHARED_DOCUMENTS = { id: 'documents', label: 'Dokumenty', icon: 'document' };

const WORKSPACES: Record<WorkspaceRole, Omit<WorkspaceConfig, 'userName' | 'shortLabel'>> = {
  admin: {
    label: 'Administrator',
    title: 'Przegląd systemu',
    subtitle: 'Dane operacyjne, dostęp i audyt',
    navigation: [
      { id: 'overview', label: 'Przegląd', icon: 'overview' },
      { id: 'patients', label: 'Pacjenci', icon: 'users' },
      SHARED_DOCUMENTS,
      { id: 'sharing', label: 'Udostępnianie', icon: 'share' },
      { id: 'audit', label: 'Dziennik audytu', icon: 'audit' },
    ],
  },
  patient: {
    label: 'Pacjent',
    title: 'Twoje dane medyczne',
    subtitle: 'Badania, dokumenty i udostępnienia',
    navigation: [
      { id: 'overview', label: 'Strona główna', icon: 'home' },
      { id: 'timeline', label: 'Oś zdrowia', icon: 'timeline' },
      SHARED_DOCUMENTS,
      { id: 'sharing', label: 'Udostępnienia', icon: 'share' },
      { id: 'profile', label: 'Moje dane', icon: 'user' },
    ],
  },
  doctor: {
    label: 'Lekarz / diagnosta',
    title: 'Workspace kliniczny',
    subtitle: 'Udostępnieni pacjenci, badania i opisy kliniczne',
    navigation: [
      { id: 'overview', label: 'Workspace kliniczny', icon: 'clipboard' },
      { id: 'patients', label: 'Moi pacjenci', icon: 'users' },
      SHARED_DOCUMENTS,
      { id: 'audit', label: 'Historia dostępu', icon: 'audit' },
    ],
  },
  therapy: {
    label: 'Fizjo / trener',
    title: 'Dane do terapii',
    subtitle: 'Pacjenci i zakres danych udostępniony do rehabilitacji',
    navigation: [
      { id: 'overview', label: 'Podsumowanie', icon: 'clipboard' },
      { id: 'patients', label: 'Moi pacjenci', icon: 'users' },
      SHARED_DOCUMENTS,
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
    const userName = session?.displayName ?? 'Użytkownik Med-Node';
    const shortLabel = session
      ? `${session.firstName.charAt(0)}${session.lastName.charAt(0)}`.toUpperCase()
      : 'MN';
    return { ...base, userName, shortLabel };
  });

  initialize(): void {
    if (!this.data() && !this.loading()) this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.api.workspace(this.role()).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (data) => {
        this.data.set(data);
        this.role.set(data.session.effectiveView);
      },
      error: (error) => this.error.set(error?.error?.message ?? 'Nie udało się pobrać danych z backendu.'),
    });
  }

  selectRole(role: WorkspaceRole): void {
    if (!this.availableViews().includes(role)) return;
    this.role.set(role);
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

  createGrant(recipientEmail: string, scope: string, expiresAt: string | null): Observable<unknown> {
    return this.api.createGrant(this.role(), {
      recipientEmail,
      scope,
      purpose: 'Udostępnienie utworzone przez użytkownika',
      expiresAt,
      studyIds: [],
    }).pipe(tap(() => this.load()));
  }

  revokeGrant(id: string): Observable<void> {
    return this.api.revokeGrant(this.role(), id).pipe(tap(() => this.load()));
  }

  addReview(studyId: string, summary: string, conclusion: string, signed: boolean): Observable<unknown> {
    return this.api.addReview(this.role(), studyId, { summary, conclusion, signed })
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

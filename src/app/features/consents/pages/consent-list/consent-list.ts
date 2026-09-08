import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { SharingApi, SharingRequest, SharingScope } from '../../services/sharing-api';

@Component({
  selector: 'app-consent-list',
  imports: [FormsModule, AppIcon],
  templateUrl: './consent-list.html',
  styleUrl: './consent-list.scss',
})
export class ConsentList implements OnInit, OnDestroy {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly sharing = inject(SharingApi);
  protected readonly requests = signal<SharingRequest[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly code = signal<{ id: string; value: string; expiresAt: string; seconds: number } | null>(null);
  protected readonly codeSeconds = signal(0);
  protected readonly codeCopied = signal(false);
  protected readonly typedCode = signal('');
  protected readonly scope = signal<SharingScope>('IMAGING_STUDIES');
  protected readonly purpose = signal('Konsultacja i ocena wybranych badań');
  protected readonly durationDays = signal(14);
  protected readonly expandedRequest = signal<string | null>(null);
  protected readonly selectedStudies = signal<Record<string, string[]>>({});
  private timer?: ReturnType<typeof setInterval>;

  protected readonly isPatient = computed(() => this.workspace.role() === 'patient');
  protected readonly pending = computed(() => this.requests().filter(request => request.status === 'PENDING'));
  protected readonly history = computed(() => this.requests().filter(request => request.status !== 'PENDING'));

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set('');
    this.sharing.requests(this.workspace.role()).subscribe({
      next: items => { this.requests.set(items); this.loading.set(false); },
      error: err => { this.loading.set(false); this.error.set(err?.error?.message ?? 'Nie udało się pobrać próśb o dostęp.'); },
    });
  }

  protected generateCode(): void {
    this.busy.set(true); this.error.set(''); this.notice.set(''); this.codeCopied.set(false);
    this.sharing.generate('patient').subscribe({
      next: value => {
        const seconds = Math.max(0, Math.ceil((Date.parse(value.expiresAt) - Date.now()) / 1000));
        this.code.set({ id: value.id, value: value.code, expiresAt: value.expiresAt, seconds });
        this.codeSeconds.set(seconds); this.busy.set(false);
        if (this.timer) clearInterval(this.timer);
        this.timer = setInterval(() => {
          const remaining = Math.max(0, Math.ceil((Date.parse(value.expiresAt) - Date.now()) / 1000));
          this.codeSeconds.set(remaining);
          if (remaining === 0) { this.code.set(null); if (this.timer) clearInterval(this.timer); }
        }, 1000);
      },
      error: err => { this.busy.set(false); this.error.set(err?.error?.message ?? 'Nie udało się wygenerować kodu.'); },
    });
  }

  protected cancelCode(): void {
    const current = this.code(); if (!current) return;
    this.busy.set(true);
    this.sharing.cancel('patient', current.id).subscribe({
      next: () => { this.code.set(null); this.codeSeconds.set(0); this.busy.set(false); if (this.timer) clearInterval(this.timer); },
      error: err => { this.busy.set(false); this.error.set(err?.error?.message ?? 'Nie udało się unieważnić kodu.'); },
    });
  }

  protected copyCode(): void {
    const value = this.code()?.value;
    if (!value) return;
    void navigator.clipboard?.writeText(value).then(() => { this.codeCopied.set(true); this.notice.set('Kod skopiowano do schowka.'); });
  }

  protected redeem(): void {
    if (this.typedCode().replace(/\s|-/g, '').length < 8 || !this.purpose().trim()) {
      this.error.set('Wpisz kod pacjenta i cel udostępnienia.'); return;
    }
    this.busy.set(true); this.error.set(''); this.notice.set('');
    this.sharing.redeem(this.workspace.role(), {
      code: this.typedCode(), scope: this.scope(), purpose: this.purpose().trim(), durationDays: this.durationDays(),
    }).subscribe({
      next: request => { this.requests.update(items => [request, ...items.filter(item => item.id !== request.id)]); this.typedCode.set(''); this.busy.set(false); this.notice.set('Prośba została wysłana. Pacjent musi ją zatwierdzić, zanim zobaczysz dane.'); },
      error: err => { this.busy.set(false); this.error.set(err?.error?.message ?? 'Kod jest nieprawidłowy albo wygasł.'); },
    });
  }

  protected toggleStudy(requestId: string, studyId: string): void {
    this.selectedStudies.update(all => {
      const current = all[requestId] ?? [];
      return { ...all, [requestId]: current.includes(studyId) ? current.filter(id => id !== studyId) : [...current, studyId] };
    });
  }

  protected isStudySelected(requestId: string, studyId: string): boolean {
    return (this.selectedStudies()[requestId] ?? []).includes(studyId);
  }

  protected approve(request: SharingRequest, approve: boolean): void {
    const studyIds = this.selectedStudies()[request.id] ?? request.studies.map(study => study.id);
    if (approve && !studyIds.length) { this.error.set('Wybierz przynajmniej jedno badanie, które ma zostać udostępnione.'); return; }
    this.busy.set(true); this.error.set('');
    this.sharing.decide('patient', request.id, approve, studyIds).subscribe({
      next: updated => { this.requests.update(items => items.map(item => item.id === updated.id ? updated : item)); this.busy.set(false); this.notice.set(approve ? 'Zgoda została zapisana. Specjalista widzi tylko wybrane badania.' : 'Prośba została odrzucona.'); },
      error: err => { this.busy.set(false); this.error.set(err?.error?.message ?? 'Nie udało się zapisać decyzji.'); },
    });
  }

  protected toggleExpanded(id: string): void { this.expandedRequest.set(this.expandedRequest() === id ? null : id); }
  protected requestLabel(request: SharingRequest): string { return request.requesterName || request.requesterEmail || 'Specjalista'; }
  protected scopeLabel(scope: string): string { return { FULL_STUDY: 'Pełny zakres historii', LAB_RESULTS: 'Wyniki laboratoryjne', IMAGING_STUDIES: 'Badania obrazowe', SELECTED_STUDIES: 'Wybrane badania' }[scope] ?? scope; }
  protected statusLabel(status: string): string { return { PENDING: 'Czeka na Twoją zgodę', APPROVED: 'Dostęp aktywny', REJECTED: 'Odrzucona', EXPIRED: 'Wygasła' }[status] ?? status; }
  protected date(value: string): string { return new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); }
}

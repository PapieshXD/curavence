import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-consent-list', imports: [FormsModule, AppIcon, StatusText, EnumLabelPipe], templateUrl: './consent-list.html', styleUrl: './consent-list.scss' })
export class ConsentList {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly createOpen = signal(false);
  protected readonly scope = signal('FULL_STUDY');
  protected readonly recipient = signal('');
  protected readonly validityDays = signal(90);
  protected readonly saving = signal(false);
  protected readonly canManage = computed(() => this.workspace.role() === 'patient' || this.workspace.role() === 'admin' || !!this.workspace.data()?.session.superuser);

  protected create(): void {
    if (!this.recipient()) { this.workspace.notify('Wybierz odbiorcę.'); return; }
    const expires = new Date(Date.now() + this.validityDays() * 86_400_000).toISOString();
    this.saving.set(true);
    this.workspace.createGrant(this.recipient(), this.scope(), expires).subscribe({
      next: () => { this.saving.set(false); this.createOpen.set(false); this.workspace.notify('Udostępnienie zapisano i zarejestrowano w audycie.'); },
      error: (error) => { this.saving.set(false); this.workspace.notify(error?.error?.message ?? 'Nie udało się utworzyć udostępnienia.'); },
    });
  }

  protected revoke(id: string): void {
    this.workspace.revokeGrant(id).subscribe({
      next: () => this.workspace.notify('Dostęp został cofnięty.'),
      error: (error) => this.workspace.notify(error?.error?.message ?? 'Nie udało się cofnąć dostępu.'),
    });
  }
  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Bezterminowo'; }
  protected initials(name: string): string { return name.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase(); }
}

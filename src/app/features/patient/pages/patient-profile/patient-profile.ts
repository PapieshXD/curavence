import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-patient-profile', imports: [FormsModule, AppIcon, EnumLabelPipe], templateUrl: './patient-profile.html', styleUrl: './patient-profile.scss' })
export class PatientProfile {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly editing = signal(false);
  protected readonly phone = signal('');
  protected readonly saving = signal(false);

  protected toggleEdit(): void {
    if (!this.editing()) this.phone.set(this.workspace.data()?.profile?.phone ?? '');
    this.editing.set(!this.editing());
  }

  protected save(): void {
    if (!this.phone().trim()) { this.workspace.notify('Numer telefonu jest wymagany.'); return; }
    this.saving.set(true);
    this.workspace.updatePhone(this.phone()).subscribe({
      next: () => { this.saving.set(false); this.editing.set(false); this.workspace.notify('Twoje dane zostały zapisane.'); },
      error: (error) => { this.saving.set(false); this.workspace.notify(error?.error?.message ?? 'Nie udało się zapisać danych.'); },
    });
  }

  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long' }).format(new Date(value)) : 'Nie podano'; }
  protected manageSecurity(): void { this.workspace.notify('Ustawienia bezpieczeństwa są aktualne.'); }
  protected downloadArchive(): void { this.workspace.notify('Przygotowujemy archiwum Twoich danych.'); }
  protected deleteAccount(): void { this.workspace.notify('Usunięcie konta wymaga dodatkowego potwierdzenia.'); }
}

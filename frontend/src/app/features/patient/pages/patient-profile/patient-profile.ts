import { Readiness } from '../../../../core/services/readiness';
import { Auth } from '../../../../core/services/auth';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Api } from '../../../../core/services/api';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-patient-profile', imports: [FormsModule, AppIcon, EnumLabelPipe], templateUrl: './patient-profile.html', styleUrl: './patient-profile.scss' })
export class PatientProfile {
  protected readonly readiness=inject(Readiness);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly auth=inject(Auth);private readonly router=inject(Router,{optional:true});
  private readonly api=inject(Api);private readonly destroy=inject(DestroyRef);
  protected readonly securityBusy=signal(false);protected readonly securityError=signal('');
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
  protected manageSecurity(): void {
    if(this.securityBusy())return;this.securityError.set('');
    const tab=window.open('about:blank','_blank');if(!tab){this.securityError.set('Zezwól na otwarcie nowej karty i spróbuj ponownie.');return;}tab.opener=null;
    this.securityBusy.set(true);
    this.api.accountSettings().pipe(takeUntilDestroyed(this.destroy)).subscribe({next:settings=>{this.securityBusy.set(false);tab.location.href=settings.accountConsoleUrl;},error:e=>{this.securityBusy.set(false);tab.close();this.securityError.set(e?.error?.message||'Ustawienia konta są teraz niedostępne. Spróbuj ponownie.');}});
    this.destroy.onDestroy(()=>{if(this.securityBusy())tab.close();});
  }
  protected logout():void {this.auth.logout();void this.router?.navigateByUrl('/login');}
  protected downloadArchive(): void { this.workspace.selectView('results');this.workspace.patientResultsMode.set(false); }
}

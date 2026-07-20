import { Component, computed, inject } from '@angular/core';
import { AuditRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { SummaryItem, SummaryStrip } from '../../../../shared/components/summary-strip/summary-strip';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-admin-dashboard', imports: [AppIcon, StatusText, SummaryStrip, EnumLabelPipe], templateUrl: './admin-dashboard.html' })
export class AdminDashboard {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly summary = computed<SummaryItem[]>(() => {
    const data = this.workspace.data()?.overview;
    return [
      { label: 'Pacjenci', value: String(data?.patients ?? 0) },
      { label: 'Badania', value: String(data?.studies ?? 0) },
      { label: 'Do weryfikacji', value: String(data?.awaitingReview ?? 0), tone: 'warning' },
      { label: 'Aktywne udostępnienia', value: String(data?.activeGrants ?? 0) },
    ];
  });
  protected readonly events = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.auditEvents ?? []).filter((event) =>
      !query || Object.values(event).join(' ').toLocaleLowerCase('pl').includes(query));
  });

  protected initials(name: string): string { return name.split(' ').map((value) => value[0]).join('').slice(0, 2).toUpperCase(); }
  protected time(value: string): string { return new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)); }
  protected action(value: string): string { return ({ LOGIN: 'Logowanie', CREATE: 'Utworzenie', UPLOAD: 'Przesłanie pliku', VIEW: 'Podgląd', DOWNLOAD: 'Pobranie', SHARE: 'Udostępnienie', REVOKE_ACCESS: 'Cofnięcie dostępu', SIGN: 'Podpisanie opisu', UPDATE: 'Aktualizacja', ACCESS_DENIED: 'Odmowa dostępu' } as Record<string, string>)[value] ?? value; }
  protected tone(event: AuditRecord): 'success' | 'danger' { return event.success ? 'success' : 'danger'; }
  protected showAudit(): void { this.workspace.selectView('audit'); }
}

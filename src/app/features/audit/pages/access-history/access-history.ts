import { Component, computed, inject } from '@angular/core';
import { AuditRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { StatusText } from '../../../../shared/components/status-text/status-text';

@Component({ selector: 'app-access-history', imports: [StatusText], templateUrl: './access-history.html', styleUrl: './access-history.scss' })
export class AccessHistory {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly events = computed(() => {
    const query = this.workspace.search().trim().toLocaleLowerCase('pl');
    return (this.workspace.data()?.auditEvents ?? []).filter((event) => !query || Object.values(event).join(' ').toLocaleLowerCase('pl').includes(query));
  });
  protected date(value: string): string { return new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); }
  protected initials(name: string): string { return name.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase(); }
  protected result(event: AuditRecord): string { return event.success ? 'Dozwolony' : (event.denialReason || 'Odmowa'); }
}

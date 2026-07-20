import { Component, inject } from '@angular/core';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StatusText } from '../../../../shared/components/status-text/status-text';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-patient-dashboard', imports: [AppIcon, StatusText, EnumLabelPipe], templateUrl: './patient-dashboard.html', styleUrl: './patient-dashboard.scss' })
export class PatientDashboard {
  protected readonly workspace = inject(WorkspaceStore);
  protected openView(view: string): void { this.workspace.selectView(view); }
  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium' }).format(new Date(value)) : 'Brak daty'; }
  protected initials(name: string): string { return name.split(' ').map((item) => item[0]).join('').slice(0, 2).toUpperCase(); }
  protected download(assetId: string | null, filename: string | null): void {
    if (assetId) this.workspace.download(assetId, filename ?? 'dokument');
    else this.workspace.notify('To badanie nie ma jeszcze dołączonego pliku.');
  }
}

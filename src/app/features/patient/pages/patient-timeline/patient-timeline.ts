import { Component, computed, inject, signal } from '@angular/core';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { EnumLabelPipe } from '../../../../shared/pipes/enum-label.pipe';

@Component({ selector: 'app-patient-timeline', imports: [AppIcon, EnumLabelPipe], templateUrl: './patient-timeline.html', styleUrl: './patient-timeline.scss' })
export class PatientTimeline {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly filter = signal('all');
  protected readonly studies = computed(() => (this.workspace.data()?.studies ?? []).filter((study) =>
    this.filter() === 'all' || this.filter() === study.typeCode));
  protected date(value: string | null): string { return value ? new Intl.DateTimeFormat('pl-PL', { dateStyle: 'long', timeStyle: 'short' }).format(new Date(value)) : 'Brak daty'; }
}

import { Component, inject } from '@angular/core';
import { WorkspaceStore } from '../../../../core/services/workspace-store';

@Component({
  selector: 'app-create-consent',
  templateUrl: './create-consent.html',
  styleUrl: './create-consent.scss',
})
export class CreateConsent {
  private readonly workspace = inject(WorkspaceStore);
  protected save(): void {
    this.workspace.notify('Zaproszenie do udostępnienia zostało zapisane.');
  }
}

import { Component, inject } from '@angular/core';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';

@Component({
  selector: 'app-document-upload',
  imports: [AppIcon],
  templateUrl: './document-upload.html',
  styleUrl: './document-upload.scss',
})
export class DocumentUpload {
  private readonly workspace = inject(WorkspaceStore);
  protected submit(): void {
    this.workspace.notify('Dokument dodano do kolejki weryfikacji.');
  }
}

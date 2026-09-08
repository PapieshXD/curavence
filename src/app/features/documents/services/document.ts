import { HttpEvent } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ClinicalResultSet,
  DocumentConversationMessage,
  DocumentQuestionResponse,
  DocumentUploadResult,
} from '../../../core/models/medical';
import { WorkspaceRole } from '../../../core/models/workspace';
import { Api } from '../../../core/services/api';

@Injectable({
  providedIn: 'root',
})
export class DocumentService {
  private readonly api = inject(Api);

  upload(
    role: WorkspaceRole,
    file: File,
    title: string,
    patientId?: string,
    performedAt?: string,
    typeCode = 'MEDICAL_DOCUMENT',
  ): Observable<HttpEvent<DocumentUploadResult>> {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('title', title.trim());
    form.append('typeCode', typeCode);
    if (patientId) form.append('patientId', patientId);
    if (performedAt) {
      form.append('performedAt', new Date(`${performedAt}T00:00:00`).toISOString());
    }
    return this.api.uploadDocument(role, form);
  }

  preview(role: WorkspaceRole, assetId: string): Observable<Blob> {
    return this.api.previewAsset(role, assetId);
  }

  clinicalResults(role: WorkspaceRole, documentId: string): Observable<ClinicalResultSet> {
    return this.api.documentClinicalResults(role, documentId);
  }

  update(
    role: WorkspaceRole,
    documentId: string,
    title: string,
    performedAt: string,
  ): Observable<void> {
    return this.api.updateDocument(role, documentId, {
      title: title.trim(),
      performedAt: performedAt ? `${performedAt}T12:00:00.000Z` : null,
    });
  }

  archive(role: WorkspaceRole, documentId: string): Observable<void> {
    return this.api.archiveDocument(role, documentId);
  }

  ask(
    role: WorkspaceRole,
    documentId: string,
    question: string,
    history: DocumentConversationMessage[],
  ): Observable<DocumentQuestionResponse> {
    return this.api.askDocument(role, documentId, question.trim(), history);
  }
}

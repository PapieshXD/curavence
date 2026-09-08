import { HttpClient, HttpEvent, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, throwError, timeout } from 'rxjs';
import {
  ClinicalResultSet,
  DocumentConversationMessage,
  DocumentQuestionResponse,
  DocumentUploadResult,
  LoginResponse,
  WorkspaceData,
  WorkspaceView,
} from '../models/medical';
import {
  ImagingStudyCreated,
  ImagingStudyDetails,
  ImagingStudyStatus,
  ImagingStudySummary,
  ViewerSession,
  ImagingAnnotation,
  SaveImagingAnnotation,
  ImagingAnalysis,
  ImagingAnalysisArtifact,
  ImagingAnalysisCreated,
  ImagingOverview,
} from '../models/imaging';
import { TokenStorage } from './token-storage';

@Injectable({
  providedIn: 'root',
})
export class Api {
  private readonly http = inject(HttpClient, { optional: true });
  private readonly tokens = inject(TokenStorage);

  login(username: string, password: string): Observable<LoginResponse> {
    return this.client().post<LoginResponse>('/api/auth/login', { username, password });
  }

  workspace(role: WorkspaceView): Observable<WorkspaceData> {
    if (!this.http) return throwError(() => new Error('HttpClient nie jest dostępny.'));
    return this.client().get<WorkspaceData>('/api/workspace', { headers: this.headers(role) }).pipe(timeout(2000));
  }

  uploadStudy(role: WorkspaceView, form: FormData): Observable<{ id: string; status: string }> {
    return this.client().post<{ id: string; status: string }>('/api/studies', form, {
      headers: this.headers(role),
    });
  }

  uploadDocument(
    role: WorkspaceView,
    form: FormData,
  ): Observable<HttpEvent<DocumentUploadResult>> {
    return this.client().post<DocumentUploadResult>('/api/documents', form, {
      headers: this.headers(role),
      observe: 'events',
      reportProgress: true,
    });
  }

  documentClinicalResults(role: WorkspaceView, documentId: string): Observable<ClinicalResultSet> {
    return this.client().get<ClinicalResultSet>(`/api/documents/${documentId}/clinical-results`, {
      headers: this.headers(role),
    });
  }

  previewAsset(role: WorkspaceView, assetId: string): Observable<Blob> {
    return this.client().get(`/api/assets/${assetId}/content?download=false`, {
      headers: this.headers(role),
      responseType: 'blob',
    });
  }

  updateDocument(
    role: WorkspaceView,
    documentId: string,
    body: { title: string; performedAt: string | null },
  ): Observable<void> {
    return this.client().put<void>(`/api/documents/${documentId}`, body, {
      headers: this.headers(role),
    });
  }

  archiveDocument(role: WorkspaceView, documentId: string): Observable<void> {
    return this.client().delete<void>(`/api/documents/${documentId}`, {
      headers: this.headers(role),
    });
  }

  askDocument(
    role: WorkspaceView,
    documentId: string,
    question: string,
    history: DocumentConversationMessage[],
  ): Observable<DocumentQuestionResponse> {
    return this.client().post<DocumentQuestionResponse>(
      `/api/documents/${documentId}/questions`,
      { question, history },
      { headers: this.headers(role) },
    );
  }

  createGrant(role: WorkspaceView, body: object): Observable<{ id: string }> {
    return this.client().post<{ id: string }>('/api/access-grants', body, {
      headers: this.headers(role),
    });
  }

  revokeGrant(role: WorkspaceView, id: string): Observable<void> {
    return this.client().delete<void>(`/api/access-grants/${id}`, { headers: this.headers(role) });
  }

  addReview(role: WorkspaceView, studyId: string, body: object): Observable<{ id: string }> {
    return this.client().post<{ id: string }>(`/api/studies/${studyId}/reviews`, body, {
      headers: this.headers(role),
    });
  }

  updatePatient(role: WorkspaceView, phone: string): Observable<void> {
    return this.client().put<void>('/api/patients/me', { phone }, { headers: this.headers(role) });
  }

  downloadAsset(role: WorkspaceView, assetId: string): Observable<Blob> {
    return this.client().get(`/api/assets/${assetId}/content?download=true`, {
      headers: this.headers(role),
      responseType: 'blob',
    });
  }

  imagingStudies(role: WorkspaceView, patientId: string): Observable<ImagingStudySummary[]> {
    return this.client().get<ImagingStudySummary[]>(
      `/api/v1/patients/${patientId}/imaging-studies`,
      { headers: this.headers(role) },
    );
  }

  imagingStudy(role: WorkspaceView, studyId: string): Observable<ImagingStudyDetails> {
    return this.client().get<ImagingStudyDetails>(`/api/v1/imaging-studies/${studyId}`, {
      headers: this.headers(role),
    });
  }

  imagingOverview(role: WorkspaceView, studyId: string, seriesId?: string): Observable<ImagingOverview> {
    return this.client().get<ImagingOverview>(`/api/v1/imaging-studies/${studyId}/overview`, {
      headers: this.headers(role),
      params: seriesId ? { seriesId } : {},
    });
  }

  imagingPreviewImage(role: WorkspaceView, studyId: string, path: string): Observable<Blob> {
    if (!path.startsWith(`/api/v1/imaging-studies/${studyId}/`) || path.includes('..')) {
      return throwError(() => new Error('Nieprawidłowy adres obrazu badania.'));
    }
    return this.client().get(path, { headers: this.headers(role), responseType: 'blob' });
  }

  imagingStatus(role: WorkspaceView, studyId: string): Observable<ImagingStudyStatus> {
    return this.client().get<ImagingStudyStatus>(`/api/v1/imaging-studies/${studyId}/status`, {
      headers: this.headers(role),
    });
  }

  uploadImagingStudy(
    role: WorkspaceView,
    patientId: string,
    form: FormData,
  ): Observable<HttpEvent<ImagingStudyCreated>> {
    return this.client().post<ImagingStudyCreated>(
      `/api/v1/patients/${patientId}/imaging-studies`,
      form,
      { headers: this.headers(role), observe: 'events', reportProgress: true },
    );
  }

  createViewerSession(
    role: WorkspaceView,
    studyId: string,
    advanced = false,
  ): Observable<ViewerSession> {
    return this.client().post<ViewerSession>(
      `/api/v1/imaging-studies/${studyId}/viewer-session?advanced=${advanced}`,
      {},
      { headers: this.headers(role), withCredentials: true },
    );
  }

  retryImagingStudy(role: WorkspaceView, studyId: string): Observable<ImagingStudyCreated> {
    return this.client().post<ImagingStudyCreated>(
      `/api/v1/imaging-studies/${studyId}/retry`, {}, { headers: this.headers(role) },
    );
  }

  imagingAnnotations(role: WorkspaceView, studyId: string): Observable<ImagingAnnotation[]> {
    return this.client().get<ImagingAnnotation[]>(`/api/v1/imaging-studies/${studyId}/annotations`, {
      headers: this.headers(role),
    });
  }

  createImagingAnnotation(
    role: WorkspaceView,
    studyId: string,
    body: SaveImagingAnnotation,
  ): Observable<ImagingAnnotation> {
    return this.client().post<ImagingAnnotation>(
      `/api/v1/imaging-studies/${studyId}/annotations`, body, { headers: this.headers(role) },
    );
  }

  deleteImagingAnnotation(role: WorkspaceView, studyId: string, annotationId: string): Observable<void> {
    return this.client().delete<void>(
      `/api/v1/imaging-studies/${studyId}/annotations/${annotationId}`,
      { headers: this.headers(role) },
    );
  }

  imagingCapabilities(role: WorkspaceView, studyId: string): Observable<{canStartAnalysis: boolean; canChangeAnalysisScope: boolean; audience: string}> {
    return this.client().get<{canStartAnalysis: boolean; canChangeAnalysisScope: boolean; audience: string}>(`/api/v1/imaging-studies/${studyId}/viewer-capabilities`, {headers: this.headers(role)});
  }

  imagingPreferences(role: WorkspaceView, studyId: string): Observable<{hiddenSeriesUids: string[] | null; lastLayout: string}> {
    return this.client().get<{hiddenSeriesUids: string[] | null; lastLayout: string}>(`/api/v1/imaging-studies/${studyId}/view-preferences`, {headers: this.headers(role)});
  }

  saveImagingPreferences(role: WorkspaceView, studyId: string, hiddenSeriesUids: string[], lastLayout: string): Observable<unknown> {
    return this.client().put(`/api/v1/imaging-studies/${studyId}/view-preferences`, {hiddenSeriesUids, lastLayout}, {headers: this.headers(role)});
  }

  imagingHistory(role: WorkspaceView, studyId: string): Observable<{id: string; version: number; status: string}[]> {
    return this.client().get<{id: string; version: number; status: string}[]>(`/api/v1/imaging-studies/${studyId}/analysis-history`, {headers: this.headers(role)});
  }

  imagingFeedback(role: WorkspaceView, studyId: string, analysisId: string): Observable<{findingId: string; action: string}[]> {
    return this.client().get<{findingId: string; action: string}[]>(`/api/v1/imaging-studies/${studyId}/analysis/${analysisId}/feedback`, {headers: this.headers(role)});
  }

  saveImagingFeedback(role: WorkspaceView, studyId: string, analysisId: string, findingId: string, action: string): Observable<unknown> {
    return this.client().post(`/api/v1/imaging-studies/${studyId}/analysis/${analysisId}/feedback`, {findingId, action}, {headers: this.headers(role)});
  }

  latestImagingAnalysis(role: WorkspaceView, studyId: string): Observable<ImagingAnalysis | null> {
    return this.client().get<ImagingAnalysis | null>(
      `/api/v1/imaging-studies/${studyId}/analyses/latest`,
      { headers: this.headers(role) },
    );
  }

  startImagingAnalysis(role: WorkspaceView, studyId: string, seriesInstanceUids?: string[]): Observable<ImagingAnalysisCreated> {
    return this.client().post<ImagingAnalysisCreated>(
      `/api/v1/imaging-studies/${studyId}/analyses`,
      { scopeMode: seriesInstanceUids ? 'SELECTED_SERIES' : 'ALL_ELIGIBLE', seriesInstanceUids: seriesInstanceUids ?? [], pipeline: 'AUTO' },
      { headers: this.headers(role) },
    );
  }

  imagingAnalysis(
    role: WorkspaceView,
    studyId: string,
    analysisId: string,
  ): Observable<ImagingAnalysis> {
    return this.client().get<ImagingAnalysis>(
      `/api/v1/imaging-studies/${studyId}/analyses/${analysisId}`,
      { headers: this.headers(role) },
    );
  }

  downloadImagingAnalysisArtifact(
    role: WorkspaceView,
    studyId: string,
    analysisId: string,
    artifact: ImagingAnalysisArtifact,
  ): Observable<Blob> {
    return this.client().get(
      `/api/v1/imaging-studies/${studyId}/analyses/${analysisId}/artifacts/${artifact.id}/content`,
      { headers: this.headers(role), responseType: 'blob' },
    );
  }

  private headers(role: WorkspaceView): HttpHeaders {
    return new HttpHeaders({
      Authorization: `Bearer ${this.tokens.accessToken()}`,
      'X-Workspace-Role': role,
    });
  }

  private client(): HttpClient {
    if (!this.http) {
      throw new Error('HttpClient nie jest dostępny w tym kontekście.');
    }
    return this.http;
  }
}

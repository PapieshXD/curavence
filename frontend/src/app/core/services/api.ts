import { KnowledgeView, KnowledgeReply } from '../models/knowledge';
import { LibraryPage, RestorePreview, MetadataHistory, ExportJob } from '../models/readiness';
import { EcgPreview, EcgCalibration, EcgSelection, EcgMorphology } from '../models/ecg';
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
import { Visit, VisitNotification } from '../models/visit';
import { ReadingGuideData, AssistantSummary, PersonalReminder } from '../models/reading-guide';

@Injectable({
  providedIn: 'root',
})
export class Api {
  knowledgeConfig() { return this.client().get<{enabled:boolean}>('/api/knowledge/config', {headers:this.headers('patient')}); }
  knowledge(role:WorkspaceView, visitId:string|null) { return this.client().get<KnowledgeView>('/api/knowledge', {headers:this.headers(role),params:visitId?{visitId}:{}}); }
  rebuildKnowledge(role:WorkspaceView, visitId:string|null) { return this.client().post<KnowledgeView>('/api/knowledge/rebuild', {}, {headers:this.headers(role),params:visitId?{visitId}:{}}); }
  askKnowledge(role:WorkspaceView,visitId:string|null,question:string) { return this.client().post<KnowledgeReply>('/api/knowledge/questions',{visitId,question},{headers:this.headers(role)}); }

  readingGuideConfig(): Observable<{enabled: boolean}> {
    return this.client().get<{enabled: boolean}>('/api/reading-guide/config', {headers: this.headers('patient')});
  }
  readingGuide(role: WorkspaceView, id: string): Observable<ReadingGuideData> {
    return this.client().get<ReadingGuideData>(`/api/documents/${id}/reading-guide`, {headers: this.headers(role)});
  }
  assistantSummary(role: WorkspaceView, id: string): Observable<AssistantSummary> {
    return this.client().post<AssistantSummary>(`/api/documents/${id}/assistant-summary`, {}, {headers: this.headers(role)});
  }
  personalReminders(): Observable<PersonalReminder[]> {
    return this.client().get<PersonalReminder[]>('/api/personal-reminders', {headers: this.headers('patient')});
  }
  savePersonalReminder(body: Omit<PersonalReminder, 'id'>, id?: string): Observable<PersonalReminder | void> {
    return id ? this.client().put<void>(`/api/personal-reminders/${id}`, body, {headers: this.headers('patient')})
      : this.client().post<PersonalReminder>('/api/personal-reminders', body, {headers: this.headers('patient')});
  }
  reportDocument(role: WorkspaceView, id: string): Observable<{documentId: string | null; title: string | null}> {
    return this.client().get<{documentId: string | null; title: string | null}>(`/api/imaging-studies/${id}/report-document`, {headers: this.headers(role)});
  }
  linkReportDocument(role: WorkspaceView, id: string, documentId: string | null): Observable<void> {
    return this.client().put<void>(`/api/imaging-studies/${id}/report-document`, {documentId}, {headers: this.headers(role)});
  }
  visitNotifications(role: WorkspaceView): Observable<VisitNotification[]> {
    return this.client().get<VisitNotification[]>('/api/visits/notifications', {headers:this.headers(role)});
  }
  readVisitNotification(role: WorkspaceView, id: string): Observable<void> {
    return this.client().put<void>(`/api/visits/notifications/${id}/read`, {}, {headers:this.headers(role)});
  }
  libraryConfig(): Observable<{searchEnabled:boolean;exportsEnabled:boolean}> {return this.client().get<{searchEnabled:boolean;exportsEnabled:boolean}>('/api/library/config',{headers:this.headers('patient')});}
  searchDocuments(role:WorkspaceView,params:Record<string,string|number|boolean>):Observable<LibraryPage> {return this.client().get<LibraryPage>('/api/documents/search',{headers:this.headers(role),params});}
  restorePreview(id:string):Observable<RestorePreview>{return this.client().get<RestorePreview>(`/api/documents/${id}/restore-preview`,{headers:this.headers('patient')});}
  restoreDocument(id:string,body:unknown):Observable<void>{return this.client().post<void>(`/api/documents/${id}/restore`,body,{headers:this.headers('patient')});}
  documentHistory(id:string):Observable<MetadataHistory[]>{return this.client().get<MetadataHistory[]>(`/api/documents/${id}/metadata-history`,{headers:this.headers('patient')});}
  createExport(body:unknown):Observable<ExportJob>{return this.client().post<ExportJob>('/api/exports',body,{headers:this.headers('patient')});}
  exportStatus(id:string):Observable<ExportJob>{return this.client().get<ExportJob>(`/api/exports/${id}`,{headers:this.headers('patient')});}
  downloadExport(id:string):Observable<Blob>{return this.client().get(`/api/exports/${id}/download`,{headers:this.headers('patient'),responseType:'blob'});}
  accountSettings():Observable<{accountConsoleUrl:string}>{return this.client().get<{accountConsoleUrl:string}>('/api/account/settings',{headers:this.headers('patient')});}
  visits(role: WorkspaceView): Observable<Visit[]> {
    return this.client().get<Visit[]>('/api/visits', {headers:this.headers(role)});
  }
  saveVisit(role: WorkspaceView, id: string | null, body: unknown): Observable<{id: string}> {
    return id ? this.client().put<{id:string}>(`/api/visits/${id}`, body, {headers:this.headers(role)})
      : this.client().post<{id:string}>('/api/visits', body, {headers:this.headers(role)});
  }
  updateVisit(role: WorkspaceView, id: string, action: string, body: unknown): Observable<void> {
    const url = `/api/visits/${id}/${action}`;
    return action === 'share' ? this.client().post<void>(url, body, {headers:this.headers(role)})
      : this.client().put<void>(url, body, {headers:this.headers(role)});
  }
  private readonly http = inject(HttpClient, { optional: true });
  private readonly tokens = inject(TokenStorage);

  login(username: string, password: string, otp?:string): Observable<LoginResponse> {
    return this.client().post<LoginResponse>('/api/auth/login', { username, password, ...(otp ? {otp} : {}) });
  }

  logout(): Observable<void> {
    return this.client().post<void>('/api/auth/logout', {refreshToken:this.tokens.refreshToken()},
      {headers:new HttpHeaders({Authorization:`Bearer ${this.tokens.accessToken()}`})});
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

  ecgPreview(role: WorkspaceView, documentId: string, calibration: EcgCalibration): Observable<EcgPreview> {
    return this.client().get<EcgPreview>(`/api/documents/${documentId}/ecg`, {
      headers: this.headers(role), params: this.ecgParams(calibration),
    });
  }

  askEcg(role: WorkspaceView, documentId: string, question: string, history: DocumentConversationMessage[], calibration: EcgCalibration, selection: EcgSelection = {channelIndex:0,startSample:0,sampleCount:0}, audience = 'patient'): Observable<DocumentQuestionResponse> {
    return this.client().post<DocumentQuestionResponse>(`/api/documents/${documentId}/ecg/questions`,
      { question, history }, { headers: this.headers(role), params: {...this.ecgParams(calibration), ...selection, audience} });
  }

  assessEcgCalibration(role:WorkspaceView, documentId:string):Observable<DocumentQuestionResponse> {
    return this.client().post<DocumentQuestionResponse>(`/api/documents/${documentId}/ecg/calibration-assessment`, {}, {headers:this.headers(role)});
  }

  ecgMorphology(role:WorkspaceView, documentId:string, calibration:EcgCalibration, selection:EcgSelection):Observable<EcgMorphology> {
    return this.client().get<EcgMorphology>(`/api/documents/${documentId}/ecg/morphology`, {
      headers:this.headers(role), params:{...this.ecgParams(calibration), ...selection},
    });
  }

  private ecgParams(calibration: EcgCalibration): Record<string,string> {
    const params: Record<string,string> = {};
    if (calibration.samplingRate) params['samplingRate'] = String(calibration.samplingRate);
    if (calibration.unit) params['unit'] = calibration.unit;
    return params;
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
    body: { title: string; performedAt: string | null; typeCode?:string; revision?:number },
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

  rewriteReview(
    role: WorkspaceView,
    studyId: string,
    summary: string,
    conclusion: string,
  ): Observable<{ summary: string; conclusion: string }> {
    return this.client().post<{ summary: string; conclusion: string }>(
      `/api/studies/${studyId}/reviews/rewrite`,
      { summary, conclusion },
      { headers: this.headers(role) },
    );
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

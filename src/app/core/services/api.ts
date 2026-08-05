import { HttpClient, HttpEvent, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { LoginResponse, WorkspaceData, WorkspaceView } from '../models/medical';
import {
  ImagingStudyCreated,
  ImagingStudyDetails,
  ImagingStudyStatus,
  ImagingStudySummary,
  ViewerSession,
  ImagingAnnotation,
  SaveImagingAnnotation,
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
    return this.client().get<WorkspaceData>('/api/workspace', { headers: this.headers(role) });
  }

  uploadStudy(role: WorkspaceView, form: FormData): Observable<{ id: string; status: string }> {
    return this.client().post<{ id: string; status: string }>('/api/studies', form, {
      headers: this.headers(role),
    });
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

  createViewerSession(role: WorkspaceView, studyId: string): Observable<ViewerSession> {
    return this.client().post<ViewerSession>(
      `/api/v1/imaging-studies/${studyId}/viewer-session`,
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

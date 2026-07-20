import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { LoginResponse, WorkspaceData, WorkspaceView } from '../models/medical';
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

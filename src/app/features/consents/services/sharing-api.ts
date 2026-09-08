import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, throwError, timeout } from 'rxjs';
import { WorkspaceView } from '../../../core/models/medical';
import { TokenStorage } from '../../../core/services/token-storage';

export type SharingScope = 'FULL_STUDY' | 'LAB_RESULTS' | 'IMAGING_STUDIES' | 'SELECTED_STUDIES';
export interface SharingCode { id: string; code: string; expiresAt: string; serverNow: string; }
export interface SharedStudyOption { id: string; title: string; typeName: string; }
export interface SharingRequest {
  id: string; status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  requesterName: string; requesterEmail: string; requesterRole: string;
  patientId: string | null; patientName: string | null; scope: SharingScope;
  purpose: string; durationDays: number; createdAt: string; expiresAt: string;
  grantId: string | null; studies: SharedStudyOption[]; serverNow: string;
}

@Injectable({ providedIn: 'root' })
export class SharingApi {
  private readonly http = inject(HttpClient, { optional: true });
  private readonly tokens = inject(TokenStorage);
  generate(role: WorkspaceView): Observable<SharingCode> {
    return this.http?.post<SharingCode>('/api/sharing/codes', {}, { headers: this.headers(role) }) ?? this.unavailable();
  }
  cancel(role: WorkspaceView, id: string): Observable<void> {
    return this.http?.delete<void>(`/api/sharing/codes/${id}`, { headers: this.headers(role) }) ?? this.unavailable();
  }
  requests(role: WorkspaceView): Observable<SharingRequest[]> {
    return this.http?.get<SharingRequest[]>('/api/sharing/requests', { headers: this.headers(role) }).pipe(timeout(2500)) ?? this.unavailable();
  }
  redeem(role: WorkspaceView, request: { code: string; scope: SharingScope; purpose: string; durationDays: number }): Observable<SharingRequest> {
    return this.http?.post<SharingRequest>('/api/sharing/requests', request, { headers: this.headers(role) }) ?? this.unavailable();
  }
  decide(role: WorkspaceView, id: string, approve: boolean, studyIds: string[]): Observable<SharingRequest> {
    return this.http?.post<SharingRequest>(`/api/sharing/requests/${id}/decision`, { approve, studyIds }, { headers: this.headers(role) }) ?? this.unavailable();
  }
  private headers(role: WorkspaceView): HttpHeaders {
    return new HttpHeaders({ Authorization: `Bearer ${this.tokens.accessToken()}`, 'X-Workspace-Role': role });
  }
  private unavailable<T>(): Observable<T> { return throwError(() => new Error('Połączenie z serwerem jest niedostępne.')); }
}

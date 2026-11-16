import { inject, Injectable } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { LoginResponse } from '../models/medical';
import { Api } from './api';
import { TokenStorage } from './token-storage';
import { WorkspaceStore } from './workspace-store';

@Injectable({
  providedIn: 'root',
})
export class Auth {
  private readonly api = inject(Api);
  private readonly tokens = inject(TokenStorage);
  private readonly workspace = inject(WorkspaceStore);

  login(username: string, password: string, otp?:string): Observable<LoginResponse> {
    this.clearSession();
    return this.api.login(username, password, otp).pipe(tap((response) => this.tokens.set(response.accessToken, response.refreshToken)));
  }

  logout(): void {
    this.api.logout().subscribe({ error: () => {} });
    this.clearSession();
  }

  private clearSession(): void {
    this.tokens.clear();
    this.workspace.reset();
  }

  isAuthenticated(): boolean {
    return this.tokens.isAuthenticated();
  }
}

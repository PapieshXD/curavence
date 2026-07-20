import { inject, Injectable } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { LoginResponse } from '../models/medical';
import { Api } from './api';
import { TokenStorage } from './token-storage';

@Injectable({
  providedIn: 'root',
})
export class Auth {
  private readonly api = inject(Api);
  private readonly tokens = inject(TokenStorage);

  login(username: string, password: string): Observable<LoginResponse> {
    return this.api.login(username, password).pipe(tap((response) => this.tokens.set(response.accessToken)));
  }

  logout(): void {
    this.tokens.clear();
  }

  isAuthenticated(): boolean {
    return this.tokens.isAuthenticated();
  }
}

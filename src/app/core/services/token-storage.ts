import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root',
})
export class TokenStorage {
  private readonly key = 'med-node.access-token';
  readonly accessToken = signal(this.read());

  set(token: string): void {
    this.accessToken.set(token);
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(this.key, token);
    }
  }

  clear(): void {
    this.accessToken.set('');
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.removeItem(this.key);
    }
  }

  isAuthenticated(): boolean {
    const token = this.accessToken();
    if (!token) return false;
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      return typeof payload.exp === 'number' && payload.exp * 1000 > Date.now();
    } catch {
      return false;
    }
  }

  private read(): string {
    return typeof sessionStorage === 'undefined' ? '' : (sessionStorage.getItem(this.key) ?? '');
  }
}

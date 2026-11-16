import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root',
})
export class TokenStorage {
  private readonly key = 'med-node.access-token';
  readonly accessToken = signal(this.read());
  private readonly refreshKey = 'curavence.refresh-token';
  private refreshPending?: Promise<void>;
  private generation = 0;

  set(token: string, refreshToken?: string): void {
    this.accessToken.set(token);
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(this.key, token);
      if (refreshToken) sessionStorage.setItem(this.refreshKey, refreshToken);
    }
  }

  clear(): void {
    this.generation++;
    this.accessToken.set('');
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.removeItem(this.key);
      sessionStorage.removeItem(this.refreshKey);
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

  refreshToken(): string { return typeof sessionStorage === 'undefined' ? '' : sessionStorage.getItem(this.refreshKey) ?? ''; }

  async refreshIfNeeded(): Promise<void> {
    const token = this.accessToken();
    if (!token) return;
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    if (payload.exp * 1000 > Date.now() + 60000 || !this.refreshToken()) return;
    if (!this.refreshPending) {
      const generation = this.generation;
      this.refreshPending = (async () => {
        const response = await fetch('/api/auth/refresh', { method: 'POST', headers: {'Content-Type':'application/json'},
          body: JSON.stringify({refreshToken:this.refreshToken()}), cache:'no-store' });
        if (!response.ok) {
          if ([400,401,403].includes(response.status) && generation === this.generation) this.clear();
          throw new Error('Session refresh failed');
        }
        const data = await response.json();
        if (generation === this.generation) this.set(data.accessToken, data.refreshToken);
      })().finally(() => this.refreshPending = undefined);
    }
    await this.refreshPending;
  }

  private read(): string {
    return typeof sessionStorage === 'undefined' ? '' : (sessionStorage.getItem(this.key) ?? '');
  }
}

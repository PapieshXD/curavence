import { Injectable, effect, inject, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { TokenStorage } from './token-storage';
import { WorkspaceStore } from './workspace-store';

export interface RealtimeChange { id: number; type: string; resource: string; resourceId: string; version: number; }

/** Transport-independent framing, including UTF-8 split across network packets. */
export class SseDecoder {
  private buffer = '';
  push(chunk: string): RealtimeChange[] {
    this.buffer += chunk;
    const result: RealtimeChange[] = [];
    let match: RegExpExecArray | null;
    while ((match = /\r?\n\r?\n/.exec(this.buffer))) {
      const block = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      const data = block.split(/\r?\n/).filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).replace(/^ /, '')).join('\n');
      if (data) result.push(JSON.parse(data));
    }
    if (this.buffer.length > 65536) throw new Error('SSE frame too large');
    return result;
  }
}

@Injectable({ providedIn: 'root' })
export class Realtime {
  private readonly tokens = inject(TokenStorage);
  private readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router);
  private revision = 0;
  private pending?: ReturnType<typeof setTimeout>;
  constructor() {
    effect(onCleanup => {
      const token = this.tokens.accessToken();
      const role = this.workspace.role();
      const generation = ++this.revision;
      const controller = new AbortController();
      onCleanup(() => { controller.abort(); clearTimeout(this.pending); });
      if (token) untracked(() => void this.listen(token, role, controller, generation));
    });
  }
  private changed(event: RealtimeChange, generation: number): void {
    if (generation !== this.revision) return;
    clearTimeout(this.pending);
    if (event.type === 'share.revoked' || event.type === 'study.deleted') {
      this.workspace.invalidateAccess();
      void this.router.navigateByUrl('/');
      this.workspace.load();
    } else {
      this.pending = setTimeout(() => {
        if (generation !== this.revision) return;
        this.workspace.realtimeRevision.update(value => value + 1);
        this.workspace.load(true);
      }, 250);
    }
  }
  private async listen(token: string, role: string, controller: AbortController, generation: number): Promise<void> {
    let cursor = 0, failures = 0;
    const headers: Record<string, string> = { Authorization: `Bearer ${token}`, 'X-Workspace-Role': role };
    const ensureOk = (response: Response) => {
      if (response.status === 401 || response.status === 403) {
        this.tokens.clear(); this.workspace.reset(); void this.router.navigateByUrl('/login'); controller.abort();
      }
      if (!response.ok) throw new Error('Realtime unavailable');
    };
    while (!controller.signal.aborted) {
      try {
        await this.tokens.refreshIfNeeded();
        if (controller.signal.aborted || generation !== this.revision) return;
        headers['Authorization'] = `Bearer ${this.tokens.accessToken()}`;
        let more: boolean;
        do {
          const response = await fetch(`/api/sync/changes?after=${cursor}&limit=100`, { headers, signal: controller.signal, cache: 'no-store' });
          ensureOk(response);
          const page = await response.json() as { events: RealtimeChange[]; hasMore: boolean };
          for (const event of page.events) if (event.id > cursor) { this.changed(event, generation); cursor = event.id; }
          more = page.hasMore;
        } while (more && !controller.signal.aborted);
        const response = await fetch('/api/events/stream', { headers: { ...headers, Accept: 'text/event-stream', 'Last-Event-ID': `${cursor}` }, signal: controller.signal, cache: 'no-store' });
        ensureOk(response);
        const reader = response.body?.getReader();
        if (!reader) throw new Error('Missing event stream');
        const decoder = new TextDecoder(), sse = new SseDecoder();
        failures = 0;
        try {
          while (!controller.signal.aborted) {
            const read = await reader.read();
            if (read.done) break;
            for (const event of sse.push(decoder.decode(read.value, { stream: true }))) {
              if (event.id > cursor) { this.changed(event, generation); cursor = event.id; }
            }
          }
        } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
      } catch {
        if (controller.signal.aborted) return;
        failures = Math.min(failures + 1, 5);
        // Shared records cannot stay open when authorization cannot be revalidated.
        if (role !== 'patient') { this.workspace.invalidateAccess(); void this.router.navigateByUrl('/'); }
      }
      if (!controller.signal.aborted) await new Promise<void>(resolve => {
        const finish = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', finish); resolve(); };
        const timer = setTimeout(finish, Math.max(1, failures * 2) * 1000);
        controller.signal.addEventListener('abort', finish, { once: true });
      });
    }
  }
}

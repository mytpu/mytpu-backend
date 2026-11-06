import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { AdminRow } from '../admin-audit/admin-audit.model';
import { ModeChange, ModeHistoryItem, ModeView } from './mode.model';

@Injectable({
  providedIn: 'root',
})
export class ModeApi {
  private readonly client = inject(ApiClient);

  getMode(): Observable<ModeView> {
    return this.client.get<ModeView>('/mode');
  }

  changeMode(change: ModeChange): Observable<ModeView> {
    return this.client.put<ModeView>('/mode', change);
  }

  getHistory(): Observable<ModeHistoryItem[]> {
    return this.client.get<AdminRow[]>('/admin-audit?type=mode&limit=20').pipe(
      map((rows) =>
        rows.map((r) => {
          let mode = 'normal';
          let reason = '';
          try {
            const parsedAfter = JSON.parse(r.after ?? '{}');
            mode = parsedAfter.mode ?? parsedAfter.value ?? 'normal';
            reason = parsedAfter.reason ?? '';
          } catch {
            // fallback
          }
          return {
            id: r.id,
            at: r.at,
            actorId: r.actorId,
            actorOwner: r.actorOwner,
            mode,
            reason,
          };
        }),
      ),
    );
  }
}

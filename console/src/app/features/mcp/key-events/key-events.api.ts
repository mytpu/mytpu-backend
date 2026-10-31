import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { KeyView, ResumeRequest } from '../keys/keys.model';
import { DecoyGroupView, ExpiringKeyView, KeyEventView } from './key-events.model';

@Injectable({
  providedIn: 'root',
})
export class KeyEventsApi {
  private readonly client = inject(ApiClient);

  getEvents(event?: string, client?: string, limit = 100): Observable<KeyEventView[]> {
    let params = new HttpParams().set('limit', limit);
    if (event && event !== 'all') {
      params = params.set('event', event);
    }
    if (client && client.trim()) {
      params = params.set('client', client.trim());
    }
    return this.client.get<KeyEventView[]>('/key-events', { params });
  }

  getExpiring(days = 14): Observable<ExpiringKeyView[]> {
    const params = new HttpParams().set('days', days);
    return this.client.get<ExpiringKeyView[]>('/keys/expiring', { params });
  }

  getDecoyGroup(id: string): Observable<DecoyGroupView> {
    return this.client.get<DecoyGroupView>(`/keys/${id}/decoy-group`);
  }

  updateDecoyGroup(id: string, members: string[]): Observable<DecoyGroupView> {
    return this.client.put<DecoyGroupView>(`/keys/${id}/decoy-group`, { members });
  }

  getAllKeys(): Observable<KeyView[]> {
    return this.client.get<KeyView[]>('/keys');
  }

  resume(id: string, request?: ResumeRequest): Observable<KeyView> {
    return this.client.post<KeyView>(`/keys/${id}/resume`, request ?? {});
  }
}

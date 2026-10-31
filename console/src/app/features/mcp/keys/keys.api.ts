import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { IssuedKey, KeyView, ResumeRequest } from './keys.model';

@Injectable({
  providedIn: 'root',
})
export class KeysApi {
  private readonly client = inject(ApiClient);

  list(): Observable<KeyView[]> {
    return this.client.get<KeyView[]>('/keys');
  }

  reissue(id: string, graceHours?: number): Observable<IssuedKey> {
    const body = graceHours !== undefined ? { graceHours } : {};
    return this.client.post<IssuedKey>(`/keys/${id}/reissue`, body);
  }

  revoke(id: string): Observable<KeyView> {
    return this.client.post<KeyView>(`/keys/${id}/revoke`, {});
  }

  resume(id: string, request?: ResumeRequest): Observable<KeyView> {
    return this.client.post<KeyView>(`/keys/${id}/resume`, request ?? {});
  }
}

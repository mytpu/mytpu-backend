import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { IssuedKey } from '../keys/keys.model';
import { IntegrationCatalogView } from '../key-wizard/key-wizard.model';
import {
  ApproveBody,
  CreateKeyRequestBody,
  KeyRequestView,
  RejectBody,
} from './key-requests.model';

@Injectable({
  providedIn: 'root',
})
export class KeyRequestsApi {
  private readonly client = inject(ApiClient);

  list(status?: string): Observable<KeyRequestView[]> {
    let params: HttpParams | undefined;
    if (status) {
      params = new HttpParams().set('status', status);
    }
    return this.client.get<KeyRequestView[]>('/key-requests', { params });
  }

  create(body: CreateKeyRequestBody): Observable<KeyRequestView> {
    return this.client.post<KeyRequestView>('/key-requests', body);
  }

  approve(id: string, body: ApproveBody): Observable<IssuedKey> {
    return this.client.post<IssuedKey>(`/key-requests/${id}/approve`, body);
  }

  reject(id: string, body: RejectBody): Observable<KeyRequestView> {
    return this.client.post<KeyRequestView>(`/key-requests/${id}/reject`, body);
  }

  catalog(): Observable<IntegrationCatalogView[]> {
    return this.client.get<IntegrationCatalogView[]>('/catalog');
  }
}

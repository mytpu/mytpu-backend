import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { AdminFilterParams, AdminRow } from './admin-audit.model';

@Injectable({
  providedIn: 'root',
})
export class AdminAuditApi {
  private readonly client = inject(ApiClient);

  list(params: AdminFilterParams): Observable<AdminRow[]> {
    let httpParams = new HttpParams();
    if (params.from) httpParams = httpParams.set('from', params.from);
    if (params.to) httpParams = httpParams.set('to', params.to);
    if (params.actor) httpParams = httpParams.set('actor', params.actor);
    if (params.type) httpParams = httpParams.set('type', params.type);
    if (params.object) httpParams = httpParams.set('object', params.object);
    if (params.before) httpParams = httpParams.set('before', params.before);
    if (params.limit) httpParams = httpParams.set('limit', params.limit);

    return this.client.get<AdminRow[]>('/admin-audit', { params: httpParams });
  }
}

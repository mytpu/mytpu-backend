import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { CallFilterParams, CallRow } from './audit.model';

@Injectable({
  providedIn: 'root',
})
export class AuditApi {
  private readonly client = inject(ApiClient);

  getCalls(params: CallFilterParams): Observable<CallRow[]> {
    let httpParams = new HttpParams();
    if (params.from) httpParams = httpParams.set('from', params.from);
    if (params.to) httpParams = httpParams.set('to', params.to);
    if (params.client) httpParams = httpParams.set('client', params.client);
    if (params.tool) httpParams = httpParams.set('tool', params.tool);
    if (params.outcome) httpParams = httpParams.set('outcome', params.outcome);
    if (params.before) httpParams = httpParams.set('before', params.before);
    if (params.limit) httpParams = httpParams.set('limit', params.limit);

    return this.client.get<CallRow[]>('/audit', { params: httpParams });
  }

  downloadCsv(params: CallFilterParams): Observable<string> {
    let httpParams = new HttpParams();
    if (params.from) httpParams = httpParams.set('from', params.from);
    if (params.to) httpParams = httpParams.set('to', params.to);
    if (params.client) httpParams = httpParams.set('client', params.client);
    if (params.tool) httpParams = httpParams.set('tool', params.tool);
    if (params.outcome) httpParams = httpParams.set('outcome', params.outcome);

    return this.client.get<string>('/audit.csv', { params: httpParams, responseType: 'text' });
  }
}

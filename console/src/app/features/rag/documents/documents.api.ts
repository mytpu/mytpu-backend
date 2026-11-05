import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { DocumentFilter, DocumentRow } from './documents.model';

@Injectable({
  providedIn: 'root',
})
export class DocumentsApi {
  private readonly client = inject(ApiClient);

  list(filter: DocumentFilter = {}): Observable<DocumentRow[]> {
    let params = new HttpParams();
    if (filter.q?.trim()) params = params.set('q', filter.q.trim());
    if (filter.source) params = params.set('source', filter.source);
    if (filter.status && filter.status !== 'all') params = params.set('status', filter.status);
    if (filter.limit) params = params.set('limit', filter.limit);
    return this.client.get<DocumentRow[]>('/rag/documents', { params });
  }

  updateStatus(id: number, status: 'active' | 'disabled'): Observable<DocumentRow> {
    return this.client.put<DocumentRow>(`/rag/documents/${id}`, { status });
  }

  delete(id: number): Observable<{ id: number; deleted: boolean }> {
    return this.client.delete<{ id: number; deleted: boolean }>(`/rag/documents/${id}`);
  }
}

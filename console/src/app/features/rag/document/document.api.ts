import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { DocumentDetail, DocumentViewResponse } from './document.model';

@Injectable({
  providedIn: 'root',
})
export class DocumentApi {
  private readonly client = inject(ApiClient);

  get(id: number): Observable<DocumentViewResponse> {
    return this.client.get<DocumentViewResponse>(`/rag/documents/${id}`);
  }

  update(id: number, change: { status?: string; access?: string }): Observable<DocumentDetail> {
    return this.client.put<DocumentDetail>(`/rag/documents/${id}`, change);
  }

  delete(id: number): Observable<{ id: number; deleted: boolean }> {
    return this.client.delete<{ id: number; deleted: boolean }>(`/rag/documents/${id}`);
  }
}

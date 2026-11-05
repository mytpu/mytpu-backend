import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpEvent, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { JobItem, SourceCreateResult, SourceItem } from './sources.model';

@Injectable({
  providedIn: 'root',
})
export class SourcesApi {
  private readonly client = inject(ApiClient);
  private readonly http = inject(HttpClient);

  list(): Observable<SourceItem[]> {
    return this.client.get<SourceItem[]>('/rag/sources');
  }

  uploadFile(file: File, title?: string): Observable<HttpEvent<SourceCreateResult>> {
    const formData = new FormData();
    formData.append('file', file);
    if (title?.trim()) {
      formData.append('title', title.trim());
    }
    const req = new HttpRequest<FormData>('POST', '/admin/api/rag/sources/files', formData, {
      reportProgress: true,
    });
    return this.http.request<SourceCreateResult>(req);
  }

  addPage(url: string, title?: string): Observable<SourceCreateResult> {
    return this.client.post<SourceCreateResult>('/rag/sources/page', {
      url: url.trim(),
      title: title?.trim() || undefined,
    });
  }

  reindex(id: number): Observable<SourceCreateResult> {
    return this.client.post<SourceCreateResult>(`/rag/sources/${id}/reindex`, {});
  }

  setState(id: number, enabled: boolean): Observable<{ id: number; enabled: boolean }> {
    return this.client.put<{ id: number; enabled: boolean }>(`/rag/sources/${id}/state`, { enabled });
  }

  delete(id: number): Observable<{ id: number; deleted: boolean }> {
    return this.client.delete<{ id: number; deleted: boolean }>(`/rag/sources/${id}`);
  }

  getJobs(limit = 20): Observable<JobItem[]> {
    return this.client.get<JobItem[]>(`/rag/jobs?limit=${limit}`);
  }

  getJob(id: number): Observable<JobItem> {
    return this.client.get<JobItem>(`/rag/jobs/${id}`);
  }
}

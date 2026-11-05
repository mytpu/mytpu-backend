import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import {
  RagOverviewData,
  RagOverviewJob,
  RagOverviewMiss,
  RagOverviewSource,
} from './overview.model';

@Injectable({
  providedIn: 'root',
})
export class RagOverviewApi {
  private readonly client = inject(ApiClient);

  getOverview(): Observable<RagOverviewData> {
    return this.client.get<RagOverviewData>('/rag/overview');
  }

  getSources(): Observable<RagOverviewSource[]> {
    return this.client.get<RagOverviewSource[]>('/rag/sources');
  }

  getJobs(limit = 5): Observable<RagOverviewJob[]> {
    return this.client.get<RagOverviewJob[]>(`/rag/jobs?limit=${limit}`);
  }

  getMisses(days = 7, limit = 4): Observable<RagOverviewMiss[]> {
    return this.client.get<RagOverviewMiss[]>(`/rag/misses?days=${days}&limit=${limit}`);
  }
}

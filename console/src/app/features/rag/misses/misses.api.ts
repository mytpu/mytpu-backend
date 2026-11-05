import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { I18n } from '../../../core/i18n/i18n.service';
import { MissGroupItem } from './misses.model';

@Injectable({
  providedIn: 'root',
})
export class MissesApi {
  private readonly client = inject(ApiClient);
  private readonly i18n = inject(I18n);

  getMisses(days = 7, limit = 50): Observable<MissGroupItem[]> {
    return this.client.get<MissGroupItem[]>(`/rag/misses?days=${days}&limit=${limit}`);
  }

  addToEval(question: string): Observable<unknown> {
    return this.client.post('/rag/eval/questions', {
      question,
      expectedUrls: [],
      hasAnswer: true,
      note: this.i18n.t('rag.misses.noteFromMisses'),
    });
  }
}

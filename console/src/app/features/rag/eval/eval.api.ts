import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { EvalQuestionItem, EvalRunItem } from './eval.model';

@Injectable({
  providedIn: 'root',
})
export class EvalApi {
  private readonly client = inject(ApiClient);

  getQuestions(): Observable<EvalQuestionItem[]> {
    return this.client.get<EvalQuestionItem[]>('/rag/eval/questions');
  }

  addQuestion(q: EvalQuestionItem): Observable<EvalQuestionItem> {
    return this.client.post<EvalQuestionItem>('/rag/eval/questions', q);
  }

  deleteQuestion(id: number): Observable<{ id: number; deleted: boolean }> {
    return this.client.delete<{ id: number; deleted: boolean }>(`/rag/eval/questions/${id}`);
  }

  importQuestions(): Observable<{ added: number; total: number }> {
    return this.client.post<{ added: number; total: number }>('/rag/eval/questions/import', {});
  }

  getRuns(): Observable<EvalRunItem[]> {
    return this.client.get<EvalRunItem[]>('/rag/eval/runs');
  }

  startRun(): Observable<{ id: number; report: unknown }> {
    return this.client.post<{ id: number; report: unknown }>('/rag/eval/runs', {});
  }
}

import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { PlaygroundAnswer, PlaygroundProbe } from './playground.model';

@Injectable({
  providedIn: 'root',
})
export class PlaygroundApi {
  private readonly client = inject(ApiClient);

  probe(probe: PlaygroundProbe): Observable<PlaygroundAnswer> {
    return this.client.post<PlaygroundAnswer>('/rag/playground', {
      query: probe.query.trim(),
      access: probe.access || 'public',
    });
  }
}

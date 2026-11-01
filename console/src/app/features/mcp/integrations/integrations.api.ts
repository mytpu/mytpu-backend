import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { IntegrationView, LimitsChange, StateChange, ToolView } from './integrations.model';

@Injectable({
  providedIn: 'root',
})
export class IntegrationsApi {
  private readonly client = inject(ApiClient);

  list(): Observable<IntegrationView[]> {
    return this.client.get<IntegrationView[]>('/catalog');
  }

  updateIntegrationState(id: string, body: StateChange): Observable<IntegrationView> {
    return this.client.put<IntegrationView>(`/integrations/${id}/state`, body);
  }

  updateToolState(name: string, body: StateChange): Observable<ToolView> {
    return this.client.put<ToolView>(`/tools/${name}/state`, body);
  }

  updateToolLimits(name: string, body: LimitsChange): Observable<ToolView> {
    return this.client.put<ToolView>(`/tools/${name}/limits`, body);
  }
}

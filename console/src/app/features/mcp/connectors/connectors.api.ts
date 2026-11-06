import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import {
  ConnectorDetails,
  ConnectorListItem,
  ConnectorSpec,
  ConnectorTestRequest,
  ConnectorTestResult,
  PublishResult,
  SaveVersionResult,
  SetSecretResult,
  UnpublishResult,
} from './connectors.model';

@Injectable({
  providedIn: 'root',
})
export class ConnectorsApi {
  private readonly client = inject(ApiClient);

  list(): Observable<ConnectorListItem[]> {
    return this.client.get<ConnectorListItem[]>('/connectors');
  }

  details(id: string): Observable<ConnectorDetails> {
    return this.client.get<ConnectorDetails>(`/connectors/${encodeURIComponent(id)}`);
  }

  saveVersion(id: string, spec: ConnectorSpec): Observable<SaveVersionResult> {
    return this.client.post<SaveVersionResult>(
      `/connectors/${encodeURIComponent(id)}/versions`,
      spec,
    );
  }

  publish(id: string, version: number): Observable<PublishResult> {
    return this.client.post<PublishResult>(
      `/connectors/${encodeURIComponent(id)}/versions/${version}/publish`,
      {},
    );
  }

  unpublish(id: string): Observable<UnpublishResult> {
    return this.client.post<UnpublishResult>(
      `/connectors/${encodeURIComponent(id)}/unpublish`,
      {},
    );
  }

  test(id: string, version: number, req: ConnectorTestRequest): Observable<ConnectorTestResult> {
    return this.client.post<ConnectorTestResult>(
      `/connectors/${encodeURIComponent(id)}/versions/${version}/test`,
      req,
    );
  }

  setSecret(id: string, name: string, value: string): Observable<SetSecretResult> {
    return this.client.put<SetSecretResult>(
      `/connectors/${encodeURIComponent(id)}/secrets/${encodeURIComponent(name)}`,
      { value },
    );
  }

  deleteSecret(id: string, name: string): Observable<SetSecretResult> {
    return this.client.delete<SetSecretResult>(
      `/connectors/${encodeURIComponent(id)}/secrets/${encodeURIComponent(name)}`,
    );
  }
}

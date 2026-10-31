import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiClient } from '../../../core/api/api';
import { IssuedKey, KeyView } from '../keys/keys.model';
import {
  IntegrationCatalogView,
  NewKeyBody,
  PermissionsChangeBody,
} from './key-wizard.model';

@Injectable({
  providedIn: 'root',
})
export class KeyWizardApi {
  private readonly client = inject(ApiClient);

  catalog(): Observable<IntegrationCatalogView[]> {
    return this.client.get<IntegrationCatalogView[]>('/catalog');
  }

  getKey(id: string): Observable<KeyView | undefined> {
    return this.client
      .get<KeyView[]>('/keys')
      .pipe(map((list) => list.find((k) => k.id === id)));
  }

  create(body: NewKeyBody): Observable<IssuedKey> {
    return this.client.post<IssuedKey>('/keys', body);
  }

  updatePermissions(
    id: string,
    body: PermissionsChangeBody,
  ): Observable<KeyView> {
    return this.client.put<KeyView>(`/keys/${id}/permissions`, body);
  }
}

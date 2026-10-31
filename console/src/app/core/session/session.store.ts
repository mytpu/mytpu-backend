import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, tap, throwError } from 'rxjs';
import { parseAdminError } from '../api/api';
import { IdleService } from './idle.service';

export interface SessionMe {
  clientId: string;
  owner: string;
  roles: string[];
  canChange: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class SessionStore {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly idleService = inject(IdleService);

  readonly key = signal<string | null>(null);
  readonly me = signal<SessionMe | null>(null);

  readonly isAuthenticated = computed(() => !!this.key() && !!this.me());
  readonly canChange = computed(() => this.me()?.canChange ?? false);
  readonly roles = computed(() => this.me()?.roles ?? []);
  readonly owner = computed(() => this.me()?.owner ?? '');

  login(apiKey: string): Observable<SessionMe> {
    const trimmed = apiKey.trim();
    const headers = new HttpHeaders({ Authorization: `Bearer ${trimmed}` });

    return this.http.get<SessionMe>('/admin/api/me', { headers }).pipe(
      tap((res) => {
        this.key.set(trimmed);
        this.me.set(res);
        this.idleService.start(() => this.logout());
      }),
      catchError((err) => throwError(() => parseAdminError(err))),
    );
  }

  logout(): void {
    this.key.set(null);
    this.me.set(null);
    this.idleService.stop();
    this.router.navigate(['/login']);
  }
}

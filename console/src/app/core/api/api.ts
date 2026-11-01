import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, catchError, throwError } from 'rxjs';

export class AdminError extends Error {
  constructor(
    public readonly status: number,
    public readonly code?: string,
    message?: string,
    public readonly raw?: unknown,
  ) {
    super(message ?? code ?? `HTTP ${status}`);
    this.name = 'AdminError';
  }
}

export function parseAdminError(err: unknown): AdminError {
  if (err instanceof AdminError) {
    return err;
  }
  if (err instanceof HttpErrorResponse) {
    const status = err.status || 0;
    let code: string | undefined;
    let message = err.message;

    if (err.error && typeof err.error === 'object') {
      const body = err.error as Record<string, unknown>;
      if (typeof body['error'] === 'string') {
        code = body['error'];
      }
      if (typeof body['message'] === 'string') {
        message = body['message'];
      }
    } else if (typeof err.error === 'string' && err.error.trim()) {
      message = err.error;
    }

    return new AdminError(status, code, message, err.error);
  }
  if (err instanceof Error) {
    return new AdminError(0, undefined, err.message, err);
  }
  return new AdminError(0, undefined, String(err), err);
}

@Injectable({
  providedIn: 'root',
})
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/admin/api';

  get<T>(path: string, options?: { headers?: HttpHeaders; params?: HttpParams; responseType?: 'json' | 'text' }): Observable<T> {
    return (
      this.http.get(
        `${this.baseUrl}${path.startsWith('/') ? path : '/' + path}`,
        options as object,
      ) as Observable<T>
    ).pipe(catchError((err) => throwError(() => parseAdminError(err))));
  }

  post<T>(path: string, body: unknown, options?: { headers?: HttpHeaders; params?: HttpParams }): Observable<T> {
    return this.http
      .post<T>(`${this.baseUrl}${path.startsWith('/') ? path : '/' + path}`, body, options)
      .pipe(catchError((err) => throwError(() => parseAdminError(err))));
  }

  put<T>(path: string, body: unknown, options?: { headers?: HttpHeaders; params?: HttpParams }): Observable<T> {
    return this.http
      .put<T>(`${this.baseUrl}${path.startsWith('/') ? path : '/' + path}`, body, options)
      .pipe(catchError((err) => throwError(() => parseAdminError(err))));
  }

  delete<T>(path: string, options?: { headers?: HttpHeaders; params?: HttpParams }): Observable<T> {
    return this.http
      .delete<T>(`${this.baseUrl}${path.startsWith('/') ? path : '/' + path}`, options)
      .pipe(catchError((err) => throwError(() => parseAdminError(err))));
  }
}

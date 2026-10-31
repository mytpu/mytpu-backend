import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { SessionStore } from './session.store';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const sessionStore = inject(SessionStore);
  const key = sessionStore.key();

  let outgoing = req;
  if (req.url.startsWith('/admin/api') && key && !req.headers.has('Authorization')) {
    outgoing = req.clone({
      setHeaders: {
        Authorization: `Bearer ${key}`,
      },
    });
  }

  return next(outgoing).pipe(
    catchError((err) => {
      if (err instanceof HttpErrorResponse && err.status === 401) {
        sessionStore.logout();
      }
      return throwError(() => err);
    }),
  );
};

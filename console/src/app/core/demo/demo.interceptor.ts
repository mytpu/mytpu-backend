import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { Observable, map, mergeMap, throwError, timer } from 'rxjs';
import { DemoDb } from './demo-db';
import { DemoRoutes } from './demo-routes';

const demoDb = new DemoDb();
const demoRoutes = new DemoRoutes(demoDb);

export const demoInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> => {
  if (!req.url.startsWith('/admin/api')) {
    return next(req);
  }

  const result = demoRoutes.handle(req);
  const delayMs = 150 + Math.floor(Math.random() * 250);

  if (result.status >= 200 && result.status < 300) {
    const res = new HttpResponse({
      status: result.status,
      body: result.body,
      url: req.url,
    });
    return timer(delayMs).pipe(map(() => res));
  }

  const err = new HttpErrorResponse({
    status: result.status,
    statusText: result.status === 401 ? 'Unauthorized' : result.status === 404 ? 'Not Found' : 'Error',
    error: result.body,
    url: req.url,
  });
  return timer(delayMs).pipe(mergeMap(() => throwError(() => err)));
};

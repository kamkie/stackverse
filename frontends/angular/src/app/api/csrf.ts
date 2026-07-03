import type { HttpInterceptorFn } from '@angular/common/http';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

function csrfToken(): string | undefined {
  return document.cookie
    .split('; ')
    .find((pair) => pair.startsWith(`${CSRF_COOKIE}=`))
    ?.slice(CSRF_COOKIE.length + 1);
}

/**
 * State-changing requests must echo the gateway's double-submit cookie as a
 * header (frontends/README.md). The token is read per request, so a 403 can
 * only mean the browser had no cookie yet — the 403 response itself carries a
 * fresh one, so a single retry with the re-read value recovers that case.
 */
export const csrfInterceptor: HttpInterceptorFn = (req, next) => {
  const method = req.method.toUpperCase();
  if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') {
    return next(req);
  }
  const token = csrfToken();
  const attempt = token ? req.clone({ setHeaders: { [CSRF_HEADER]: token } }) : req;
  return next(attempt).pipe(
    catchError((error: unknown) => {
      const fresh = csrfToken();
      if (
        error instanceof HttpErrorResponse &&
        error.status === 403 &&
        fresh !== undefined &&
        fresh !== token
      ) {
        return next(req.clone({ setHeaders: { [CSRF_HEADER]: fresh } }));
      }
      return throwError(() => error);
    }),
  );
};

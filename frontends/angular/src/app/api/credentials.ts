import type { HttpInterceptorFn } from '@angular/common/http';

/**
 * The SPA holds no auth state — every `/api/*` and `/auth/*` call is
 * authenticated purely by the gateway's session cookie, and state-changing
 * calls also carry the XSRF-TOKEN cookie (docs/ARCHITECTURE.md,
 * frontends/README.md). `withFetch()` defaults to `credentials: 'same-origin'`,
 * which silently drops both cookies the moment the SPA and gateway are served
 * from different origins. Forcing `withCredentials` on every request makes the
 * contract — "calls the API with credentials included" — explicit and keeps
 * cross-origin deployments authenticated. Angular's built-in XSRF interceptor
 * does not set this, so it lives here rather than being assumed.
 */
export const credentialsInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ withCredentials: true }));

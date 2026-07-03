import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { credentialsInterceptor } from './api/credentials';
import { csrfInterceptor } from './api/csrf';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // The fetch backend goes through globalThis.fetch, which the dev-only
    // action log patches for its [api] lines (docs/LOGGING.md §9).
    // credentialsInterceptor forces `withCredentials` on every call so the
    // session + XSRF cookies survive a cross-origin deployment; csrfInterceptor
    // then echoes the double-submit token on state-changing calls.
    provideHttpClient(
      withFetch(),
      withInterceptors([credentialsInterceptor, csrfInterceptor]),
    ),
  ],
};

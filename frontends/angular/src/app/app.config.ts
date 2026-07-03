import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { csrfInterceptor } from './api/csrf';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // The fetch backend goes through globalThis.fetch, which the dev-only
    // action log patches for its [api] lines (docs/LOGGING.md §9).
    provideHttpClient(withFetch(), withInterceptors([csrfInterceptor])),
  ],
};

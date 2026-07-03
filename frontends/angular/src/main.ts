import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

async function bootstrap() {
  // In dev, mirror console output and uncaught errors to the dev server so
  // browser logs show up in the terminal and .logs/frontend.log, and log user
  // actions (clicks, submits, navigation, API outcomes) through the same
  // channel so "clicked X" is traceable to what it caused. The ngDevMode
  // guard is build-time constant, so none of this ships in the production
  // bundle (docs/LOGGING.md §9).
  if (typeof ngDevMode !== 'undefined' && ngDevMode) {
    const { forwardConsoleToDevServer } = await import('./app/dev/forward-console');
    forwardConsoleToDevServer();
    const { logUserActions } = await import('./app/dev/log-user-actions');
    logUserActions();
  }

  await bootstrapApplication(App, appConfig);
}

bootstrap().catch((err) => console.error(err));

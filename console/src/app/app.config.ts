import { ApplicationConfig, inject, provideAppInitializer, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/session/auth.interceptor';
import { DEMO_INTERCEPTORS } from './core/demo/demo-interceptors';
import { ThemeService } from './core/theme/theme.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    // Без withViewTransitions: в фоновой вкладке переход обрывается (InvalidStateError) и отменяет навигацию после входа
    provideRouter(routes),
    provideHttpClient(
      withInterceptors([
        ...DEMO_INTERCEPTORS,
        authInterceptor,
      ]),
    ),
    // Тема ставится до первого экрана, в том числе на экране входа
    provideAppInitializer(() => {
      inject(ThemeService);
    }),
  ],
};

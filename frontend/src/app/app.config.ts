import { ApplicationConfig } from '@angular/core';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './http-auth.interceptor';
import { errorInterceptor } from './services/error-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    // Order matters: auth first (attaches the JWT), then errors so the
    // error interceptor can observe the post-auth response.
    provideHttpClient(withXhr(), withInterceptors([authInterceptor, errorInterceptor])),
    provideRouter(routes),
  ]
};

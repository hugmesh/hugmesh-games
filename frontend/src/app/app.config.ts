import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './http-auth.interceptor';
import { errorInterceptor } from './services/error-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    // Angular 22 defaults to ZONELESS change detection when no zone
    // provider is given, but this codebase (host + every federated
    // remote it mounts) mutates plain component fields from HTTP
    // subscribe callbacks and timers. Under zoneless those mutations
    // schedule NOTHING: the view renders on the triggering click and
    // then freezes — e.g. the maker's build pane stayed at
    // "Building …" forever while the build reached DONE server-side.
    // zone.js is already in the polyfills; opt back into zone-based
    // CD until the components migrate to signals.
    provideZoneChangeDetection(),
    // Order matters: auth first (attaches the JWT), then errors so the
    // error interceptor can observe the post-auth response.
    provideHttpClient(withXhr(), withInterceptors([authInterceptor, errorInterceptor])),
    provideRouter(routes),
  ]
};

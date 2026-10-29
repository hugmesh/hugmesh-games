import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';
import { authInterceptor } from './app/auth.interceptor';

// Bootstrap the standalone dev-shell application. Only executed when the remote
// is served standalone (`ng serve games` / the screenshot harness); in
// production the host shell bootstraps its own Angular app and federates
// `./routes` + `./admin-routes` directly — this bootstrap is not involved.
//
// HttpClient is provided here because GamesApiService calls the same-origin
// /games/api/** endpoints. In production it resolves from the host shell's root
// injector (providedIn: 'root'). withFetch() uses the native fetch API; the
// authInterceptor sends every request withCredentials so the HttpOnly
// bff_session cookie rides along — the browser holds no bearer token, the
// fe-gateway mints it server-side.
bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
  ],
}).catch((err) => console.error(err));

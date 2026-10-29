import { HttpInterceptorFn } from '@angular/common/http';

/** Sends the HttpOnly `bff_session` cookie with every outgoing HttpClient
 *  request so the fe-gateway's BffCookieToBearerWebFilter can mint the bearer
 *  server-side (BffJwtExchangeWebFilter). The browser never holds a bearer
 *  token for the games BFF — no games-realm login, no localStorage token. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req.clone({ withCredentials: true }));
};

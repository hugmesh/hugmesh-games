package com.threewizards.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Auth", description = "Authentication proxy. Exchanges credentials for a Casdoor JWT without exposing client_secret to the browser.")
@RestController
@RequestMapping("/api/auth")
public class CasdoorAuthController {

    private final WebClient casdoorClient;
    private final String clientId;
    private final String clientSecret;

    public CasdoorAuthController(
            @Value("${casdoor.endpoint}") String endpoint,
            @Value("${casdoor.client-id}") String clientId,
            @Value("${casdoor.client-secret}") String clientSecret) {
        this.casdoorClient = WebClient.create(endpoint);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    record LoginRequest(String username, String password) {}

    @Operation(
        summary = "Exchange credentials for an access token",
        description = """
            Proxies an OAuth2 Resource Owner Password Credentials grant to Casdoor.
            The `client_secret` is injected server-side — it is never sent to the browser.
            The returned `access_token` is a signed RS256 JWT; store it in `sessionStorage`
            and attach it as `Authorization: Bearer <token>` on subsequent API requests.

            ```
            Browser                  API (:8080)                 Casdoor (:8000, internal)
              │                         │                               │
              │  POST /api/auth/token   │                               │
              │  {username, password}   │                               │
              │────────────────────────►│                               │
              │                         │  POST /api/login/oauth/       │
              │                         │       access_token            │
              │                         │  grant_type=password          │
              │                         │  client_id=…                  │
              │                         │  client_secret=…  (server)    │
              │                         │  username=…  password=…       │
              │                         │───────────────────────────────►
              │                         │                               │
              │                         │◄─── {access_token, …} ────────│
              │                         │                               │
              │◄────────────────────────│
              │  {access_token,         │
              │   token_type,           │
              │   expires_in}           │

            Reactor chain:
            Mono.just(req)
             ──●──► WebClient.post(casdoor, form)
                     .retrieve()
                     .bodyToMono(Map)
             ──────────────────────────●─|
                                        {access_token, token_type, expires_in, id_token}
            ```

            Store and use the token:
            ```js
            const { access_token, expires_in } = await authService.login(u, p);
            // AuthService stores to sessionStorage; HTTP interceptor attaches Bearer automatically
            ```
            """)
    @PostMapping("/token")
    @SuppressWarnings("unchecked")
    Mono<Map<String, Object>> token(@RequestBody LoginRequest req) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", req.username());
        form.add("password", req.password());
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        return casdoorClient.post()
                .uri("/api/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m);
    }
}

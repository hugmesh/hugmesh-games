package games.hugmesh.bff.controller;

import games.hugmesh.bff.service.UserDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Map;

@Tag(name = "Auth", description = "Authentication proxy. Exchanges credentials for a Casdoor JWT without exposing client_secret to the browser.")
@RestController
@RequestMapping("/api/auth")
public class CasdoorAuthController {

    private final WebClient casdoorClient;
    private final String clientId;
    private final String clientSecret;
    private final UserDirectoryService directory;

    public CasdoorAuthController(
            @Value("${casdoor.endpoint}") String endpoint,
            @Value("${casdoor.client-id}") String clientId,
            @Value("${casdoor.client-secret}") String clientSecret,
            UserDirectoryService directory) {
        this.casdoorClient = WebClient.create(endpoint);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.directory = directory;
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
                .map(m -> (Map<String, Object>) m)
                // Side-effect: prime the user directory's name → id
                // cache from the JWT we just minted. The friends graph
                // resolves usernames against this cache; without the
                // priming step a friend request to a user who has
                // never logged in would 404 (Casdoor's get-user API
                // is admin-only and refuses our app credentials).
                .doOnNext(this::cacheLoginFromTokenResponse);
    }

    /**
     * Decode the JWT's payload to pull the (name, sub) pair onto the
     * directory's in-memory cache. Skips on any malformed input — the
     * cache is best-effort and the controller keeps the response
     * unchanged regardless.
     */
    @SuppressWarnings("unchecked")
    private void cacheLoginFromTokenResponse(Map<String, Object> resp) {
        Object accessToken = resp.get("access_token");
        if (!(accessToken instanceof String token) || token.isBlank()) return;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(
                    parts[1].replace('-', '-').replace('_', '_'));
            String json = new String(decoded);
            Map<String, Object> claims = parseFlatJson(json);
            Object name = claims.get("name");
            Object sub = claims.get("sub");
            if (name instanceof String nameStr && sub instanceof String subStr) {
                directory.cacheLogin(nameStr, subStr);
            }
        } catch (Exception ignore) {
            // best-effort cache prime — never block the login flow
        }
    }

    /**
     * Tiny JSON object parser that pulls top-level string fields. We
     * only need {@code name} and {@code sub}; pulling in Jackson here
     * is overkill and the controller already stays minimal.
     */
    private static Map<String, Object> parseFlatJson(String json) {
        Map<String, Object> out = new java.util.HashMap<>();
        // Match `"key":"value"` pairs at any depth — JWT payload has
        // both top-level strings and nested objects but the keys we
        // care about (name, sub) are top-level only.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([a-zA-Z0-9_]+)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        while (m.find()) {
            out.putIfAbsent(m.group(1), m.group(2));
        }
        return out;
    }
}

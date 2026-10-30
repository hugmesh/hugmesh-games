package games.hugmesh.bff.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Admin-only proxy that returns the Casdoor role list for the
 * {@code threewizards} organisation. Used by the upcoming admin UI's
 * "grant role" dropdown so users pick from a known set of role names
 * instead of typing free text — and so the admin can never accidentally
 * reference a role Casdoor doesn't know about.
 *
 * <p>Why proxy rather than call Casdoor directly from the browser:
 * Casdoor's admin API requires the platform admin token, which lives
 * server-side in {@code casdoor.client-secret}. Exposing it to the SPA
 * would let any authenticated player enumerate roles too.
 */
@Tag(name = "Roles", description = "Admin-only proxy for the Casdoor role catalog.")
@RestController
@RequestMapping("/api/admin/roles")
public class RoleProxyController {

    private final WebClient casdoor;
    private final String clientId;
    private final String clientSecret;
    private final String organization;

    public RoleProxyController(@Value("${casdoor.endpoint}") String endpoint,
                               @Value("${casdoor.client-id}") String clientId,
                               @Value("${casdoor.client-secret}") String clientSecret,
                               @Value("${casdoor.organization}") String organization) {
        this.casdoor = WebClient.create(endpoint);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.organization = organization;
    }

    public record RoleSummary(String name, String displayName, String description, boolean enabled) {}

    @Operation(
        summary = "List Casdoor roles for the threewizards organisation (admin only).",
        description = """
            Calls Casdoor's `/api/get-roles?owner=threewizards` with the
            client_credentials token, then re-shapes the response so the
            admin UI gets only the fields it needs (name, displayName,
            description, isEnabled). Casdoor is the source of truth — this
            endpoint never caches, never persists.
            """)
    @GetMapping
    @PreAuthorize("hasRole('GAMES_ADMIN')")
    @SuppressWarnings("unchecked")
    public Mono<List<RoleSummary>> list() {
        // get-roles is admin-only on the Casdoor side; client_credentials
        // grant gives us the platform-admin token without storing the
        // human admin's password anywhere.
        return casdoor.post()
                .uri("/api/login/oauth/access_token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("grant_type=client_credentials"
                        + "&client_id=" + clientId
                        + "&client_secret=" + clientSecret)
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(tokenResp -> {
                    String token = (String) ((Map<String, Object>) tokenResp).get("access_token");
                    if (token == null) {
                        return Mono.just(List.<RoleSummary>of());
                    }
                    return casdoor.get()
                            .uri("/api/get-roles?owner=" + organization)
                            .header("Authorization", "Bearer " + token)
                            .retrieve()
                            .bodyToMono(List.class)
                            .map(raw -> ((List<Map<String, Object>>) raw).stream()
                                    .map(m -> new RoleSummary(
                                            (String) m.getOrDefault("name", ""),
                                            (String) m.getOrDefault("displayName", ""),
                                            (String) m.getOrDefault("description", ""),
                                            Boolean.TRUE.equals(m.get("isEnabled"))))
                                    .toList());
                })
                .onErrorReturn(List.of());
    }
}

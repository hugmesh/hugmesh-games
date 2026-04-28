package games.snapdragon.bff.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Casdoor usernames to their stable Casdoor user id (UUID).
 *
 * <p>The friend graph + blocklist tables key on {@code casdoor_id}
 * (UUID), matching the {@code session_members} table so the lobby
 * filter can intersect "blocked users" against "session creators"
 * without an identifier mismatch. Players type usernames into the
 * UI; this service is the bridge between those two worlds.
 *
 * <p>Three call shapes:
 * <ul>
 *   <li>{@link #resolveOrPassthrough(String)} — the controller-side
 *       entry. If the input looks like a UUID, returns it as-is;
 *       otherwise calls Casdoor.</li>
 *   <li>{@link #resolveByName(String)} — strict lookup; emits 404
 *       on miss.</li>
 *   <li>{@link #cacheLogin(String, String)} — populates the cache
 *       on every successful JWT validation so the n-th friend
 *       request avoids the round trip.</li>
 * </ul>
 *
 * <p>The cache is in-process and unbounded — the user count grows
 * slowly and the cost of an entry is small. A future SRE pass can
 * migrate to a TTL/LRU cache if the user table expands.
 */
@Service
public class UserDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryService.class);

    private final WebClient casdoor;
    private final String clientId;
    private final String clientSecret;
    private final String organization;

    /** name → casdoor_id (UUID). */
    private final ConcurrentHashMap<String, String> nameToId = new ConcurrentHashMap<>();
    /** casdoor_id (UUID) → name. Populated from {@link #cacheLogin}
     *  so the friends UI can render a username next to a stored
     *  relationship without a Casdoor round-trip. */
    private final ConcurrentHashMap<String, String> idToName = new ConcurrentHashMap<>();

    @Autowired
    public UserDirectoryService(@Value("${casdoor.endpoint}") String endpoint,
                                @Value("${casdoor.client-id}") String clientId,
                                @Value("${casdoor.client-secret}") String clientSecret,
                                @Value("${casdoor.organization}") String organization) {
        this(WebClient.create(endpoint), clientId, clientSecret, organization);
    }

    /**
     * Package-private — unit tests pass a pre-built WebClient pointing
     * at a stub server. Production callers go through the @Value-bound
     * primary constructor.
     */
    UserDirectoryService(WebClient casdoor, String clientId, String clientSecret, String organization) {
        this.casdoor = casdoor;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.organization = organization;
    }

    /**
     * Heuristic: if the input is already a Casdoor UUID, return it
     * unchanged; otherwise resolve via the directory. Lets the
     * controller accept either form so a script with a known UUID
     * doesn't pay the Casdoor round-trip.
     */
    public Mono<String> resolveOrPassthrough(String input) {
        if (input == null || input.isBlank()) {
            return Mono.empty();
        }
        if (looksLikeUuid(input)) {
            return Mono.just(input);
        }
        return resolveByName(input);
    }

    /**
     * Strict username → casdoor_id resolution. Cached on hit; emits
     * empty when the user isn't known. Callers that need a 404 wrap
     * with {@code switchIfEmpty(...)}.
     *
     * <p>Casdoor's {@code /api/get-user} is admin-only — Basic auth
     * with the app credentials is rejected with "Please login first".
     * We follow the {@code RoleProxyController} pattern: mint a
     * client-credentials access token first, then call get-user with
     * Bearer auth.
     */
    public Mono<String> resolveByName(String name) {
        String hit = nameToId.get(name);
        if (hit != null) {
            return Mono.just(hit);
        }
        return casdoor.post()
                .uri("/api/login/oauth/access_token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("grant_type=client_credentials"
                        + "&client_id=" + clientId
                        + "&client_secret=" + clientSecret)
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(tokenResp -> {
                    Object t = tokenResp.get("access_token");
                    if (!(t instanceof String token) || token.isBlank()) {
                        return Mono.empty();
                    }
                    return casdoor.get()
                            .uri("/api/get-user?owner=" + organization + "&name=" + name)
                            .header("Authorization", "Bearer " + token)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .flatMap(this::extractId);
                })
                .doOnNext(id -> nameToId.put(name, id))
                .onErrorResume(err -> {
                    log.debug("[user-directory] resolveByName({}) failed: {}", name, err.toString());
                    return Mono.empty();
                });
    }

    /**
     * Populate the cache on every successful JWT validation — a
     * single invariant the SecurityFilter or an authn listener can
     * call as a side-effect so subsequent friend lookups by name
     * avoid the round trip.
     */
    public void cacheLogin(String name, String casdoorId) {
        if (name != null && !name.isBlank() && casdoorId != null && !casdoorId.isBlank()) {
            nameToId.put(name, casdoorId);
            idToName.put(casdoorId, name);
        }
    }

    /**
     * Reverse lookup: casdoor_id (UUID) → name. Returns the UUID
     * itself when the cache doesn't know the user yet, so the friends
     * UI always has *something* to render. The name appears once the
     * target has logged in (the auth controller primes the cache).
     */
    public String displayNameFor(String casdoorId) {
        if (casdoorId == null) return "";
        String n = idToName.get(casdoorId);
        return n != null ? n : casdoorId;
    }

    static boolean looksLikeUuid(String s) {
        // 8-4-4-4-12 hex with dashes, case-insensitive. We don't need
        // strict version checks — Casdoor mints v4s but a future
        // migration could mint v7s and we'd still want to recognise
        // them as opaque ids.
        if (s.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!isHex(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    @SuppressWarnings("unchecked")
    private Mono<String> extractId(Map<?, ?> body) {
        // Casdoor wraps the result as {status, msg, data, data2} — the
        // user object lives in `data`. Empty `data` (with status='ok')
        // means the user wasn't found.
        Object data = body.get("data");
        if (!(data instanceof Map<?, ?> user)) return Mono.empty();
        Object id = user.get("id");
        if (id instanceof String s && !s.isBlank()) {
            return Mono.just(s);
        }
        return Mono.empty();
    }

}

package games.hugmesh.bff.service;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.GamesAdminPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
 *
 * <h2>Two realms</h2>
 *
 * <p>A BFF that trusts two Casdoors sees two unrelated user directories, and
 * usernames are only unique inside one: mesh {@code alice} and games {@code alice}
 * can be different people. So a name is only ever looked up in the CALLER's realm,
 * and both caches remember which realm a name came from; a name from the other
 * realm reads back qualified ({@code alice (id.example)}). A friend request by
 * username can therefore never land on a same-named stranger in the other realm.
 *
 * <p>Each realm needs its own client to reach its Casdoor. {@code casdoor.*} is the
 * games realm's (or the only realm's). {@code app.tenancy.systems-directory.*} is
 * the optional second one. Without it, a mesh caller's lookups are answered from
 * the cache alone, so a name resolves once that person has signed in here.
 */
@Service
public class UserDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryService.class);

    /** One realm's Casdoor, reachable with its own app credentials. */
    record Directory(WebClient client, String clientId, String clientSecret, String organization) {}

    /** A cached name and the realm it belongs to. */
    private record RealmName(String realm, String name) {}

    /** Realm key for one-realm BFFs, where a token's issuer tells us nothing. */
    private static final String ONLY_REALM = "";

    /** {@code casdoor.*}: the games realm's Casdoor, or the only one. */
    private final Directory primary;
    /** Decides whose lookups {@link #primary} answers: every caller on a one-realm
     *  BFF, games-realm callers on a two-realm one. */
    private final Predicate<String> primaryServes;
    /** {@code app.tenancy.systems-directory.*}, or {@code null} when not configured. */
    private final Directory secondary;
    /** Issuer {@link #secondary} answers for; {@code ""} when not configured. */
    private final String secondaryIssuer;
    /** Whether names need a realm at all; false keeps a one-realm BFF's single map. */
    private final boolean multiRealm;

    /** (realm, name) → casdoor_id (UUID). */
    private final ConcurrentHashMap<RealmName, String> nameToId = new ConcurrentHashMap<>();
    /** casdoor_id (UUID) → (realm, name). Populated from {@link #cacheLogin}
     *  so the friends UI can render a username next to a stored
     *  relationship without a Casdoor round-trip. */
    private final ConcurrentHashMap<String, RealmName> idToName = new ConcurrentHashMap<>();

    @Autowired
    public UserDirectoryService(@Value("${casdoor.endpoint}") String endpoint,
                                @Value("${casdoor.client-id}") String clientId,
                                @Value("${casdoor.client-secret}") String clientSecret,
                                @Value("${casdoor.organization}") String organization,
                                @Value("${app.tenancy.systems-directory.issuer:}") String systemsIssuer,
                                @Value("${app.tenancy.systems-directory.endpoint:}") String systemsEndpoint,
                                @Value("${app.tenancy.systems-directory.organization:}") String systemsOrganization,
                                @Value("${app.tenancy.systems-directory.client-id:}") String systemsClientId,
                                @Value("${app.tenancy.systems-directory.client-secret:}") String systemsClientSecret,
                                DomainRegistry domainRegistry,
                                GamesAdminPolicy gamesAdminPolicy) {
        this(new Directory(WebClient.create(endpoint), clientId, clientSecret, organization),
                domainRegistry.isMultiIssuer(),
                gamesAdminPolicy::isGamesRealmIssuer,
                systemsIssuer,
                allSet(systemsIssuer, systemsEndpoint, systemsOrganization, systemsClientId, systemsClientSecret)
                        ? new Directory(WebClient.create(systemsEndpoint.trim()), systemsClientId.trim(),
                                systemsClientSecret.trim(), systemsOrganization.trim())
                        : null);
    }

    /**
     * Package-private — unit tests pass pre-built WebClients pointing
     * at a stub server. Production callers go through the @Value-bound
     * primary constructor.
     */
    UserDirectoryService(Directory primary, boolean multiRealm, Predicate<String> primaryServes,
                         String secondaryIssuer, Directory secondary) {
        this.primary = primary;
        this.multiRealm = multiRealm;
        this.primaryServes = primaryServes;
        this.secondaryIssuer = normalizeIssuer(secondaryIssuer);
        this.secondary = secondary;
    }

    private static boolean allSet(String... values) {
        for (String v : values) {
            if (v == null || v.isBlank()) return false;
        }
        return true;
    }

    private static String normalizeIssuer(String issuer) {
        if (issuer == null) return "";
        String t = issuer.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /** The realm a token's issuer belongs to, for cache keys. */
    private String realmOf(String issuer) {
        return multiRealm ? normalizeIssuer(issuer) : ONLY_REALM;
    }

    /** The Casdoor that answers for this caller, or {@code null} when none does. */
    private Directory directoryFor(String callerIssuer) {
        if (!multiRealm || primaryServes.test(callerIssuer)) return primary;
        String realm = normalizeIssuer(callerIssuer);
        if (secondary != null && !realm.isEmpty() && realm.equals(secondaryIssuer)) return secondary;
        return null;
    }

    /**
     * Heuristic: if the input is already a Casdoor UUID, return it
     * unchanged; otherwise resolve via the directory. Lets the
     * controller accept either form so a script with a known UUID
     * doesn't pay the Casdoor round-trip.
     *
     * @param callerIssuer the {@code iss} of the caller's validated token: a name is
     *                     looked up in that realm only
     */
    public Mono<String> resolveOrPassthrough(String input, String callerIssuer) {
        if (input == null || input.isBlank()) {
            return Mono.empty();
        }
        if (looksLikeUuid(input)) {
            return Mono.just(input);
        }
        return resolveByName(input, callerIssuer);
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
     *
     * @param callerIssuer the {@code iss} of the caller's validated token. Only that
     *                     realm's cache and Casdoor are consulted; a realm with no
     *                     configured client answers from the cache alone.
     */
    public Mono<String> resolveByName(String name, String callerIssuer) {
        if (!isValidUsername(name)) {
            return Mono.empty();
        }
        RealmName key = new RealmName(realmOf(callerIssuer), name);
        String hit = nameToId.get(key);
        if (hit != null) {
            return Mono.just(hit);
        }
        Directory directory = directoryFor(callerIssuer);
        if (directory == null) {
            log.debug("[user-directory] no Casdoor client for realm {}; '{}' answered from cache only",
                    key.realm(), name);
            return Mono.empty();
        }
        WebClient casdoor = directory.client();
        return casdoor.post()
                .uri("/api/login/oauth/access_token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("grant_type=client_credentials"
                        + "&client_id=" + directory.clientId()
                        + "&client_secret=" + directory.clientSecret())
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(tokenResp -> {
                    Object t = tokenResp.get("access_token");
                    if (!(t instanceof String token) || token.isBlank()) {
                        return Mono.empty();
                    }
                    return casdoor.get()
                            .uri(u -> u.path("/api/get-user")
                                    .queryParam("owner", directory.organization())
                                    .queryParam("name", name)
                                    .build())
                            .header("Authorization", "Bearer " + token)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .flatMap(this::extractId);
                })
                .doOnNext(id -> nameToId.put(key, id))
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
     *
     * @param issuer the {@code iss} of the token that named this user
     */
    public void cacheLogin(String issuer, String name, String casdoorId) {
        if (name != null && !name.isBlank() && casdoorId != null && !casdoorId.isBlank()) {
            RealmName entry = new RealmName(realmOf(issuer), name);
            nameToId.put(entry, casdoorId);
            idToName.put(casdoorId, entry);
        }
    }

    /**
     * Reverse lookup: casdoor_id (UUID) → name. Returns the UUID
     * itself when the cache doesn't know the user yet, so the friends
     * UI always has *something* to render. The name appears once the
     * target has logged in (the auth controller primes the cache).
     *
     * @param callerIssuer the {@code iss} of the viewer's token. A name from another
     *                     realm carries that realm's host, because the bare name could
     *                     equally be someone in the viewer's own realm.
     */
    public String displayNameFor(String casdoorId, String callerIssuer) {
        if (casdoorId == null) return "";
        RealmName n = idToName.get(casdoorId);
        if (n == null) return casdoorId;
        return n.realm().equals(realmOf(callerIssuer))
                ? n.name()
                : n.name() + " (" + realmLabel(n.realm()) + ")";
    }

    private static String realmLabel(String realm) {
        try {
            String host = URI.create(realm).getHost();
            return host != null ? host : realm;
        } catch (IllegalArgumentException notAUri) {
            return realm;
        }
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

    /**
     * Casdoor usernames allow alphanumerics, hyphens, underscores, and dots (per its
     * own registration validation). Reject anything outside that set before the Casdoor
     * round-trip so a crafted name cannot inject query-string parameters even after the
     * UriBuilder encodes it — the encoding is the primary defence; this is defence-in-depth.
     */
    static boolean isValidUsername(String name) {
        if (name == null || name.isBlank() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') return false;
        }
        return true;
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

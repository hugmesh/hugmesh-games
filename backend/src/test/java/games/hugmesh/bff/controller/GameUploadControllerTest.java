package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.PublisherGameScope;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.PublisherService;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Controller-level proof of the two security-critical pieces of upload logic that live
 * ONLY in {@link GameUploadController} (so they're outside {@code DomainScopeServiceTest}):
 * (1) the per-game BRAND guard composed into the post-stage {@code requireScope} hook —
 * a brand-X uploader cannot overwrite an existing brand-Y title (SUPERADMIN bypasses; a
 * brand-new slug passes; a global 'default' title passes the guard but is preserved by
 * first-writer-wins in the service); and (2) the fail-closed {@code 403} when the
 * uploader's issuer resolves to no known brand.
 *
 * <p>The {@code requireScope} composition is now a package-private method, tested
 * directly — no need to mock the concrete {@code GameUploadService} (which Byte Buddy
 * can't instrument under Java 25). The controller is built with real
 * {@link PublisherService} / {@link DomainScopeService} over mocked INTERFACE repos.
 */
class GameUploadControllerTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository gameRepo;
    private PublisherGameScopeRepository pubScopes;

    private GameUploadController controllerFor(DomainRegistry registry) {
        gameRepo = mock(GameRepository.class);
        pubScopes = mock(PublisherGameScopeRepository.class);
        PublisherService publisher = new PublisherService(pubScopes, mock(ModGameScopeRepository.class));
        DomainScopeService scope = new DomainScopeService(gameRepo, mock(GameSessionRepository.class));
        // uploadService / uploads are unused by requireScope() and by the null-domain
        // early return, so null is safe here (and dodges mocking the concrete service).
        return new GameUploadController(null, null, publisher, scope, registry,
                games.hugmesh.bff.security.GamesAdminPolicy.gamesRealm());
    }

    private static DomainRegistry multiBrand() {
        return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
    }

    private void stubGame(String slug, String domain) {
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(slug))).thenReturn(Mono.just(g));
    }

    private static Jwt jwt(String issuer, String sub, List<String> roles) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", roles).build();
    }

    private static void assertForbidden(Mono<?> m) {
        StepVerifier.create(m)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    // ----------------------------------------------------------------- ADMIN brand guard

    @Test
    void adminRequireScopeDeniesForeignBrandAllowsOwnAndNewSlug() {
        GameUploadController c = controllerFor(multiBrand());
        Jwt adminX = jwt(ISS_X, "admin-x", List.of("admin"));
        Function<String, Mono<Void>> requireScope = c.requireScope(adminX, "brandx", true, false);

        // existing brand-Y title -> a brand-X admin is refused (cross-brand overwrite).
        stubGame("gy", "brandy");
        assertForbidden(requireScope.apply("gy"));

        // own-brand title -> allowed.
        stubGame("gx", "brandx");
        StepVerifier.create(requireScope.apply("gx")).verifyComplete();

        // brand-NEW slug (no row yet) -> allowed; stamped brandx on create.
        when(gameRepo.findBySlug(eq("fresh"))).thenReturn(Mono.empty());
        StepVerifier.create(requireScope.apply("fresh")).verifyComplete();

        // existing GLOBAL ('default') title -> guard passes (default is unscoped);
        // first-writer-wins in GameUploadService preserves 'default' (proven there).
        stubGame("shared", "default");
        StepVerifier.create(requireScope.apply("shared")).verifyComplete();
    }

    @Test
    void superadminRequireScopeBypassesForeignBrand() {
        GameUploadController c = controllerFor(multiBrand());
        Jwt root = jwt(ISS_X, "root", List.of("admin", "superadmin"));
        Function<String, Mono<Void>> requireScope = c.requireScope(root, "brandx", true, true);

        stubGame("gy", "brandy");
        StepVerifier.create(requireScope.apply("gy")).verifyComplete();   // cross-domain bypass
    }

    @Test
    void publisherRequireScopeChecksOwnershipThenBrand() {
        GameUploadController c = controllerFor(multiBrand());
        Jwt pub = jwt(ISS_X, "pub-x", List.of("publisher"));
        Function<String, Mono<Void>> requireScope = c.requireScope(pub, "brandx", false, false);

        // owns "gx" + own brand -> allowed.
        when(pubScopes.findActive(eq("pub-x"), eq("gx"))).thenReturn(Mono.just(new PublisherGameScope()));
        stubGame("gx", "brandx");
        StepVerifier.create(requireScope.apply("gx")).verifyComplete();

        // ownership denied -> 403 from requireOwns; the brand check's Mono is built
        // (findBySlug returns a lazy Mono in production) but never subscribed because
        // .then() short-circuits on the ownership error. Stub findBySlug so the mock
        // returns a non-null Mono at construction time.
        when(pubScopes.findActive(eq("pub-x"), eq("foreign"))).thenReturn(Mono.empty());
        when(gameRepo.findBySlug(eq("foreign"))).thenReturn(Mono.empty());
        assertForbidden(requireScope.apply("foreign"));
    }

    @Test
    void publisherRequireScopeMatchesSeededUsernameRow() {
        // Liquibase-seeded scope rows key on the Casdoor *username* (the
        // subject is a UUID re-minted every fresh boot), so ownership must
        // also match the JWT name claim — the exact shape of the seeded
        // publisher-flatland fixture uploading six-realms.
        GameUploadController c = controllerFor(multiBrand());
        Jwt pub = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(ISS_X).subject("8558e928-re-minted-uuid")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("publisher"))
                .claim("name", "publisher-flatland")
                .build();
        Function<String, Mono<Void>> requireScope = c.requireScope(pub, "brandx", false, false);

        when(pubScopes.findActive(eq("8558e928-re-minted-uuid"), eq("six-realms"))).thenReturn(Mono.empty());
        when(pubScopes.findActive(eq("publisher-flatland"), eq("six-realms")))
                .thenReturn(Mono.just(new PublisherGameScope()));
        // brand-new slug: no games row yet, so the brand guard passes.
        when(gameRepo.findBySlug(eq("six-realms"))).thenReturn(Mono.empty());
        StepVerifier.create(requireScope.apply("six-realms")).verifyComplete();
    }

    // ----------------------------------------------------------------- fail-closed

    @Test
    void untrustedIssuerFailsClosedBeforeInstall() {
        GameUploadController c = controllerFor(multiBrand());
        // issuer not in the registry -> Roles.domainOf returns null -> 403 before any
        // install. The controller's uploadService is null here; a clean 403 (no NPE)
        // proves the early return fires before the install path is ever touched.
        Jwt unknown = jwt("https://casdoor.unknown.example", "x", List.of("admin"));
        assertForbidden(c.upload(mock(FilePart.class), unknown));
    }

    // ----------------------------------------------------------------- single-tenant no-op

    @Test
    void singleTenantRequireScopeIsInert() {
        GameUploadController c = controllerFor(new DomainRegistry("", ""));
        // No tenancy config -> caller domain "default", games 'default' -> gate passes.
        Jwt adminAnyIssuer = jwt(ISS_X, "admin", List.of("admin"));
        Function<String, Mono<Void>> requireScope = c.requireScope(adminAnyIssuer, "default", true, false);

        stubGame("g", "default");
        StepVerifier.create(requireScope.apply("g")).verifyComplete();
    }
}

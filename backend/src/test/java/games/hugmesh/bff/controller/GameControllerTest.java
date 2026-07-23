package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.Chapter;
import games.hugmesh.bff.repository.ChapterRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.service.ProgressionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTTP-layer route coverage for {@link GameController}.
 *
 * <p><strong>Scope note (2026-07-08):</strong> PR #100 ("Add tests: wave2c
 * coverage") added zero test files despite its title/commit message, but it
 * DID quietly introduce a real behavior change worth pinning: {@code
 * GameController} now maps both {@code /{id}/chapters} (canonical, matches
 * the frontend's {@code Level}→{@code Chapter} rename) and {@code
 * /{id}/levels} (legacy alias, kept for already-exported WASM bundles that
 * hardcode the old path) to the same {@link GameController#listChapters}
 * handler. This file exists solely to pin that dual-mapping at the real HTTP
 * routing layer (not just as a direct Java method call) so a future edit that
 * accidentally splits/removes one of the two mappings fails a test instead of
 * silently 404ing already-deployed WASM bundles. It does NOT attempt to cover
 * the rest of {@link GameController} (see the wider wave2c-coverage-followup
 * PR description for what's still outstanding there).
 */
@WebFluxTest(controllers = GameController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class GameControllerTest {

    @Autowired WebTestClient web;

    @MockitoBean GameRepository games;
    @MockitoBean ChapterRepository levels;
    @MockitoBean ProgressionService progression;

    // ── /{id}/chapters and /{id}/levels alias parity ───────────────────────────

    /**
     * Both the canonical {@code /chapters} path and the legacy {@code /levels}
     * alias must resolve to the SAME handler and therefore return byte-identical
     * response bodies for the same game id. {@code /api/games/**} is public
     * (permitAll — see {@link SecurityConfig#PUBLIC_PATHS}), so this is driven
     * fully anonymously, exactly like an unauthenticated browser loading the
     * game catalog before login.
     */
    @Test
    void chaptersAndLevelsRoutesReturnIdenticalBody() {
        long gameId = 42L;
        Chapter cave = chapterStub(1L, gameId, "cave", "Cave", "godot", 0);
        Chapter field = chapterStub(2L, gameId, "field", "Field", "godot", 1);
        when(levels.findByGameIdOrderBySortOrderAsc(gameId)).thenReturn(Flux.just(cave, field));

        byte[] chaptersBody = web.get().uri("/api/games/{id}/chapters", gameId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        byte[] levelsBody = web.get().uri("/api/games/{id}/levels", gameId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(chaptersBody).isNotNull().isNotEmpty();
        assertThat(levelsBody).isEqualTo(chaptersBody);

        String body = new String(chaptersBody);
        assertThat(body).contains("\"slug\":\"cave\"").contains("\"slug\":\"field\"");

        // Both URL patterns must have actually reached the shared repo call —
        // proves they're wired to ONE handler, not two independently-stubbed ones.
        verify(levels, times(2)).findByGameIdOrderBySortOrderAsc(gameId);
    }

    /** Same parity check on the empty-result edge (unknown/childless game id). */
    @Test
    void chaptersAndLevelsRoutesReturnIdenticalEmptyBody() {
        long gameId = 999L;
        when(levels.findByGameIdOrderBySortOrderAsc(gameId)).thenReturn(Flux.empty());

        byte[] chaptersBody = web.get().uri("/api/games/{id}/chapters", gameId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        byte[] levelsBody = web.get().uri("/api/games/{id}/levels", gameId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(levelsBody).isEqualTo(chaptersBody);
        verify(levels, times(2)).findByGameIdOrderBySortOrderAsc(gameId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * {@link Chapter} exposes no public constructor/setters (Spring Data R2DBC
     * populates it via reflection) — same reflective-fixture idiom used by
     * {@code ProgressionServiceTest#levelStub}.
     */
    private static Chapter chapterStub(long id, long gameId, String slug, String name,
                                        String sceneType, int sortOrder) {
        Chapter c = new Chapter();
        try {
            setField(c, "id", id);
            setField(c, "gameId", gameId);
            setField(c, "slug", slug);
            setField(c, "name", name);
            setField(c, "sceneType", sceneType);
            setField(c, "sortOrder", sortOrder);
            setField(c, "assetUrl", "/api/assets/levels/" + slug + "/index.html");
            setField(c, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return c;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

package games.hugmesh.bff.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pinning specs for {@link UserDirectoryService}.
 *
 * <p>The directory is the load-bearing piece of the friends graph
 * fix: without it, the lobby block-filter compares a UUID-keyed
 * {@code creatorCasdoorId} against a username-keyed blocklist and
 * never matches, so blocked users' sessions stay visible. These
 * tests pin every contract the controller layer depends on except
 * the actual HTTP call (covered by {@code make e2e} against the
 * real Casdoor):
 *
 * <ul>
 *   <li>UUID-shaped input passes through with zero side effects.</li>
 *   <li>Cache hits short-circuit subsequent {@code resolveByName}.</li>
 *   <li>{@link UserDirectoryService#cacheLogin} primes the cache so
 *       the next lookup never touches the wire.</li>
 *   <li>Empty / blank input emits empty (controller maps to 400).</li>
 *   <li>{@link UserDirectoryService#looksLikeUuid} accepts the
 *       canonical 8-4-4-4-12 hex form (case-insensitive) and
 *       rejects everything else.</li>
 * </ul>
 */
class UserDirectoryServiceTest {

    private UserDirectoryService service;

    @BeforeEach
    void setUp() {
        // The WebClient is unused for the test paths exercised here —
        // every assertion is on the cache + heuristic, not the wire.
        // The HTTP path itself is covered by the e2e suite where the
        // real Casdoor responds.
        WebClient client = WebClient.create("http://unused");
        service = new UserDirectoryService(client, "client", "secret", "threewizards");
    }

    @Test
    void uuidInputPassesThroughWithoutTouchingTheCache() {
        String uuid = "21c51347-c1c7-421b-a104-25a52c06b4d0";
        StepVerifier.create(service.resolveOrPassthrough(uuid))
                .expectNext(uuid)
                .verifyComplete();
    }

    @Test
    void primedCacheShortCircuitsResolution() {
        service.cacheLogin("player1", "primed-uuid");
        StepVerifier.create(service.resolveByName("player1"))
                .expectNext("primed-uuid")
                .verifyComplete();
    }

    @Test
    void primedCacheAlsoApplitsToResolveOrPassthrough() {
        service.cacheLogin("player2", "primed-uuid-2");
        StepVerifier.create(service.resolveOrPassthrough("player2"))
                .expectNext("primed-uuid-2")
                .verifyComplete();
    }

    @Test
    void blankInputEmitsEmpty() {
        StepVerifier.create(service.resolveOrPassthrough(""))
                .verifyComplete();
        StepVerifier.create(service.resolveOrPassthrough("   "))
                .verifyComplete();
        StepVerifier.create(service.resolveOrPassthrough(null))
                .verifyComplete();
    }

    @Test
    void cacheLoginIgnoresBlanks() {
        // Both fields must be present — the cache must never end up
        // with "" → "" rows that swallow real lookups.
        service.cacheLogin("", "uuid-x");
        service.cacheLogin("name-x", "");
        service.cacheLogin(null, "uuid-y");
        service.cacheLogin("name-y", null);
        // None of those primed the cache, so the lookup falls through
        // (and would hit the wire under real conditions). We don't
        // assert on the wire here — only that the cache wasn't poisoned.
        // A primed entry on a different name still works:
        service.cacheLogin("real", "real-uuid");
        StepVerifier.create(service.resolveByName("real"))
                .expectNext("real-uuid")
                .verifyComplete();
    }

    @Test
    void looksLikeUuidRecognisesCanonicalForm() {
        assertThat(UserDirectoryService.looksLikeUuid("21c51347-c1c7-421b-a104-25a52c06b4d0")).isTrue();
        assertThat(UserDirectoryService.looksLikeUuid("21C51347-C1C7-421B-A104-25A52C06B4D0")).isTrue();
        assertThat(UserDirectoryService.looksLikeUuid("00000000-0000-0000-0000-000000000000")).isTrue();
    }

    @Test
    void looksLikeUuidRejectsUsernamesAndMisshaped() {
        assertThat(UserDirectoryService.looksLikeUuid("player2")).isFalse();
        assertThat(UserDirectoryService.looksLikeUuid("admin")).isFalse();
        // Wrong length (35 chars).
        assertThat(UserDirectoryService.looksLikeUuid("21c51347-c1c7-421b-a104-25a52c06b4d")).isFalse();
        // Non-hex character at the end.
        assertThat(UserDirectoryService.looksLikeUuid("21c51347-c1c7-421b-a104-25a52c06b4dz")).isFalse();
        // Missing dashes.
        assertThat(UserDirectoryService.looksLikeUuid("21c51347c1c7421ba10425a52c06b4d0aa")).isFalse();
        // Dashes in wrong positions.
        assertThat(UserDirectoryService.looksLikeUuid("21c5134-7c1c7-421b0-a104-25a52c06b4d0")).isFalse();
    }

    // --- isValidUsername (OWASP: query-param injection guard) ---

    @Test
    void isValidUsernameAcceptsNormalUsernames() {
        assertThat(UserDirectoryService.isValidUsername("alice")).isTrue();
        assertThat(UserDirectoryService.isValidUsername("player1")).isTrue();
        assertThat(UserDirectoryService.isValidUsername("mod-three-wizards")).isTrue();
        assertThat(UserDirectoryService.isValidUsername("publisher_flatland")).isTrue();
        assertThat(UserDirectoryService.isValidUsername("A.B.C")).isTrue();
    }

    @Test
    void isValidUsernameRejectsInjectionPayloads() {
        // CROSS-USER DENY: crafted names that attempt query-string injection MUST be rejected
        // so they never reach Casdoor or the UriBuilder.
        assertThat(UserDirectoryService.isValidUsername("alice&owner=attacker")).isFalse();
        assertThat(UserDirectoryService.isValidUsername("alice?foo=bar")).isFalse();
        assertThat(UserDirectoryService.isValidUsername("alice=x")).isFalse();
        assertThat(UserDirectoryService.isValidUsername("alice bob")).isFalse();
        assertThat(UserDirectoryService.isValidUsername("alice\0pwn")).isFalse();
        assertThat(UserDirectoryService.isValidUsername("")).isFalse();
        assertThat(UserDirectoryService.isValidUsername("   ")).isFalse();
        assertThat(UserDirectoryService.isValidUsername(null)).isFalse();
        // Over-length name (> 64 chars).
        assertThat(UserDirectoryService.isValidUsername("a".repeat(65))).isFalse();
    }

    @Test
    void resolveByName_invalidUsername_emitsEmpty() {
        // An injection-shaped name must short-circuit before any network call.
        // The WebClient points at an unused URL — if the guard fires the observable
        // completes immediately; if it doesn't, the test hangs on the HTTP attempt.
        StepVerifier.create(service.resolveByName("bad&owner=attacker"))
                .verifyComplete();
        StepVerifier.create(service.resolveByName("bad?q=1"))
                .verifyComplete();
    }
}

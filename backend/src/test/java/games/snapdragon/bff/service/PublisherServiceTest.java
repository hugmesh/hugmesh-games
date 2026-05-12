package games.snapdragon.bff.service;

import games.snapdragon.bff.model.ModGameScope;
import games.snapdragon.bff.model.PublisherGameScope;
import games.snapdragon.bff.repository.ModGameScopeRepository;
import games.snapdragon.bff.repository.PublisherGameScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Specs for {@link PublisherService} — the per-game publisher scope.
 *
 * <p>Contract: a publisher can grant the mod role only for games they
 * own (publisher_game_scopes). Admin grants bypass the scope check;
 * those land via the role-admin controller, not this service.
 */
class PublisherServiceTest {

    private PublisherGameScopeRepository publisherScopes;
    private ModGameScopeRepository modScopes;
    private PublisherService service;

    @BeforeEach
    void setUp() {
        publisherScopes = mock(PublisherGameScopeRepository.class);
        modScopes       = mock(ModGameScopeRepository.class);
        service         = new PublisherService(publisherScopes, modScopes);

        lenient().when(modScopes.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(publisherScopes.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // PublisherService.grantMod chains `.then(modScopes.findActive(...))`
        // before requireOwns has a chance to short-circuit, so even the
        // 403 path needs findActive to return a real Mono. Lenient
        // default keeps every test from having to stub it explicitly.
        lenient().when(modScopes.findActive(anyString(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void grantModRequiresOwnership() {
        when(publisherScopes.findActive("rogue-publisher", "three-wizards")).thenReturn(Mono.empty());

        StepVerifier.create(service.grantMod("rogue-publisher", "alice", "three-wizards"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void grantModSucceedsWhenOwned() {
        when(publisherScopes.findActive("twiat-pub", "three-wizards"))
                .thenReturn(Mono.just(scope("twiat-pub", "three-wizards")));
        when(modScopes.findActive("alice", "three-wizards")).thenReturn(Mono.empty());

        StepVerifier.create(service.grantMod("twiat-pub", "alice", "three-wizards"))
                .assertNext(row -> {
                    assertThat(row.getCasdoorId()).isEqualTo("alice");
                    assertThat(row.getGameSlug()).isEqualTo("three-wizards");
                    assertThat(row.getGrantedByCasdoorId()).isEqualTo("twiat-pub");
                })
                .verifyComplete();
    }

    @Test
    void grantModIsIdempotent() {
        when(publisherScopes.findActive("twiat-pub", "three-wizards"))
                .thenReturn(Mono.just(scope("twiat-pub", "three-wizards")));
        ModGameScope existing = new ModGameScope();
        existing.setCasdoorId("alice");
        existing.setGameSlug("three-wizards");
        existing.setGrantedAt(Instant.now());
        when(modScopes.findActive("alice", "three-wizards")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.grantMod("twiat-pub", "alice", "three-wizards"))
                .assertNext(row -> assertThat(row).isSameAs(existing))
                .verifyComplete();
    }

    @Test
    void revokeModRequiresOwnershipAndExistingScope() {
        when(publisherScopes.findActive("twiat-pub", "three-wizards"))
                .thenReturn(Mono.just(scope("twiat-pub", "three-wizards")));
        when(modScopes.findActive("alice", "three-wizards")).thenReturn(Mono.empty());

        StepVerifier.create(service.revokeMod("twiat-pub", "alice", "three-wizards"))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(404))
                .verify();
    }

    @Test
    void revokeModSoftDeletes() {
        when(publisherScopes.findActive("twiat-pub", "three-wizards"))
                .thenReturn(Mono.just(scope("twiat-pub", "three-wizards")));
        ModGameScope existing = new ModGameScope();
        existing.setCasdoorId("alice");
        existing.setGameSlug("three-wizards");
        existing.setGrantedAt(Instant.now());
        when(modScopes.findActive("alice", "three-wizards")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.revokeMod("twiat-pub", "alice", "three-wizards"))
                .verifyComplete();
        assertThat(existing.getRevokedAt()).isNotNull();
        assertThat(existing.getRevokedByCasdoorId()).isEqualTo("twiat-pub");
    }

    private static PublisherGameScope scope(String casdoorId, String gameSlug) {
        PublisherGameScope row = new PublisherGameScope();
        row.setCasdoorId(casdoorId);
        row.setGameSlug(gameSlug);
        row.setGrantedByCasdoorId("system-seed");
        row.setGrantedAt(Instant.now());
        return row;
    }
}

package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.CharacterDto;
import games.hugmesh.bff.model.CharacterItem;
import games.hugmesh.bff.model.CharacterQuest;
import games.hugmesh.bff.model.DashboardDto;
import games.hugmesh.bff.model.PlayerCharacter;
import games.hugmesh.bff.repository.CharacterItemRepository;
import games.hugmesh.bff.repository.CharacterQuestRepository;
import games.hugmesh.bff.repository.CharacterSkillRepository;
import games.hugmesh.bff.repository.ItemRepository;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.PlayerSessionRepository;
import games.hugmesh.bff.repository.QuestRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.GameStateService;
import games.hugmesh.bff.service.GameTimeService;
import games.hugmesh.bff.service.ProgressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Object-level authorization (IDOR) regression suite for {@link CharacterController}.
 *
 * <p>Each per-id endpoint now funnels through the controller's {@code ownedCharacter}
 * gate (ASVS 4.1.3 / OWASP A01:2021). These tests prove a player who is NOT the
 * character owner is rejected with 403 on read, patch, dashboard, quest-complete,
 * and item-use — and that the OWNER (and an ADMIN) still succeed. Before the fix
 * any authenticated player could enumerate character ids and read/mutate any other
 * player's character.
 */
class CharacterControllerAuthzTest {

    private PlayerCharacterRepository characters;
    private GameStateService gameStateService;
    private GameTimeService gameTimeService;
    private ProgressionService progression;
    private CharacterQuestRepository charQuests;
    private QuestRepository questRepo;
    private PlayerSessionRepository sessionRepo;
    private CharacterSkillRepository charSkills;
    private CharacterItemRepository charItems;
    private ItemRepository itemRepo;
    private CharacterController controller;

    private static final String OWNER = "owner-sub-1";
    private static final String ATTACKER = "attacker-sub-2";

    @BeforeEach
    void setUp() {
        characters       = mock(PlayerCharacterRepository.class);
        // GameStateService is a concrete class whose constructor wires reactor
        // sinks; under JDK 25 the inline mock maker can't instrument it. The
        // controller only calls its void notifyCharacterStatChange side-effect,
        // so a hand-rolled no-op subclass (recording invocations) is both
        // sufficient and deterministic.
        gameStateService = new RecordingGameStateService();
        // GameTimeService is likewise a concrete class the JDK-25 inline mock
        // maker can't instrument; a no-op subclass returning a null snapshot is
        // all the dashboard path needs.
        gameTimeService  = new StubGameTimeService();
        // ProgressionService is a concrete class too — same JDK-25 mock-maker
        // limitation. A subclass returning an empty games list is all the
        // dashboard path needs; the IDOR gate runs strictly before it.
        progression      = new StubProgressionService();
        charQuests       = mock(CharacterQuestRepository.class);
        questRepo        = mock(QuestRepository.class);
        sessionRepo      = mock(PlayerSessionRepository.class);
        charSkills       = mock(CharacterSkillRepository.class);
        charItems        = mock(CharacterItemRepository.class);
        itemRepo         = mock(ItemRepository.class);
        // Single-tenant default registry: the IDOR ownership gate (ownedCharacter)
        // does not consult tenancy, so an empty-config DomainRegistry ("default"
        // domain) satisfies the branch's added collaborator without altering the
        // authorization behaviour these tests assert.
        controller = new CharacterController(characters, gameStateService, gameTimeService,
                progression, charQuests, questRepo, sessionRepo, charSkills, charItems, itemRepo,
                new DomainRegistry("", ""));
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    void getDeniesNonOwnerWith403() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.get(5L, jwt(ATTACKER, "player")))
                .expectErrorSatisfies(err -> assertForbidden(err))
                .verify();
    }

    @Test
    void getAllowsOwner() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.get(5L, jwt(OWNER, "player")))
                .assertNext(dto -> assertThat(dto.id()).isEqualTo(5L))
                .verifyComplete();
    }

    @Test
    void getAllowsAdminOnAnyCharacter() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.get(5L, jwt(ATTACKER, "admin")))
                .assertNext(dto -> assertThat(dto.id()).isEqualTo(5L))
                .verifyComplete();
    }

    @Test
    void getMissingCharacterIs404NotAnOwnershipOracle() {
        when(characters.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(controller.get(99L, jwt(ATTACKER, "player")))
                .expectErrorSatisfies(err -> assertStatus(err, HttpStatus.NOT_FOUND))
                .verify();
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void listScopesToCallerOwnCharacters() {
        when(characters.findByCasdoorId(ATTACKER)).thenReturn(Mono.just(character(8L, ATTACKER)));

        StepVerifier.create(controller.list(jwt(ATTACKER, "player")))
                .assertNext(dto -> assertThat(dto.id()).isEqualTo(8L))
                .verifyComplete();
        // The full-roster findAll() is reserved for admins.
        verify(characters, never()).findAll();
    }

    @Test
    void listReturnsFullRosterForAdmin() {
        when(characters.findAll()).thenReturn(Flux.just(character(1L, OWNER), character(2L, ATTACKER)));

        StepVerifier.create(controller.list(jwt("any-admin", "admin")))
                .expectNextCount(2)
                .verifyComplete();
    }

    // ── update (CRITICAL: cross-player stat manipulation) ──────────────────────

    @Test
    void updateDeniesNonOwnerAndDoesNotPersist() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.update(5L, Map.of("level", 999, "attack", 500), jwt(ATTACKER, "player")))
                .expectErrorSatisfies(err -> assertForbidden(err))
                .verify();

        // The attacker's patch must never reach the database, and no SSE event
        // may broadcast a mutation that did not happen.
        verify(characters, never()).save(any(PlayerCharacter.class));
        assertThat(((RecordingGameStateService) gameStateService).statChangeCount).isZero();
    }

    @Test
    void updateAllowsOwner() {
        PlayerCharacter c = character(5L, OWNER);
        when(characters.findById(5L)).thenReturn(Mono.just(c));
        when(characters.save(any(PlayerCharacter.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(controller.update(5L, Map.of("level", 12), jwt(OWNER, "player")))
                .assertNext(dto -> assertThat(dto.level()).isEqualTo(12))
                .verifyComplete();
        verify(characters).save(any(PlayerCharacter.class));
    }

    // ── dashboard ──────────────────────────────────────────────────────────────

    @Test
    void dashboardDeniesNonOwnerBeforeAssemblingData() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.dashboard(5L, jwt(ATTACKER, "player")))
                .expectErrorSatisfies(err -> assertForbidden(err))
                .verify();

        // No co-player history / quest / game queries may run for a denied caller.
        verify(sessionRepo, never()).findHistoryByCasdoorId(any());
        verify(charQuests, never()).findByCharacterId(anyLong());
    }

    @Test
    void dashboardAllowsOwner() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));
        when(charQuests.findByCharacterId(5L)).thenReturn(Flux.empty());
        when(sessionRepo.findHistoryByCasdoorId(OWNER)).thenReturn(Flux.empty());

        StepVerifier.create(controller.dashboard(5L, jwt(OWNER, "player")))
                .assertNext((DashboardDto d) -> assertThat(d.character().id()).isEqualTo(5L))
                .verifyComplete();
    }

    // ── completeQuest ──────────────────────────────────────────────────────────

    @Test
    void completeQuestDeniesNonOwnerAndDoesNotGrantRewards() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.completeQuest(5L, 3L, jwt(ATTACKER, "player")))
                .expectErrorSatisfies(err -> assertForbidden(err))
                .verify();

        // The quest lookup / reward-skill grant must never run for a non-owner.
        verify(charQuests, never()).findByCharacterIdAndQuestId(anyLong(), anyLong());
        verify(charSkills, never()).save(any());
    }

    @Test
    void completeQuestAllowsOwner() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));
        CharacterQuest cq = characterQuest(5L, 3L);
        when(charQuests.findByCharacterIdAndQuestId(5L, 3L)).thenReturn(Mono.just(cq));
        when(charQuests.save(any(CharacterQuest.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(questRepo.findById(3L)).thenReturn(Mono.empty());

        StepVerifier.create(controller.completeQuest(5L, 3L, jwt(OWNER, "player")))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("completed"))
                .verifyComplete();
    }

    // ── useFirstItem ───────────────────────────────────────────────────────────

    @Test
    void useFirstItemDeniesNonOwnerAndDoesNotTouchInventory() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.useFirstItem(5L, jwt(ATTACKER, "player")))
                .expectErrorSatisfies(err -> assertForbidden(err))
                .verify();

        // The victim's inventory must never be read or decremented.
        verify(charItems, never()).findByCharacterId(anyLong());
        verify(charItems, never()).save(any(CharacterItem.class));
    }

    @Test
    void useFirstItemAllowsOwner() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));
        when(charItems.findByCharacterId(5L)).thenReturn(Flux.empty());

        // Empty inventory => 404 "no usable item"; the point is the ownership
        // gate passed (we did not get a 403) and the inventory query ran.
        StepVerifier.create(controller.useFirstItem(5L, jwt(OWNER, "player")))
                .expectErrorSatisfies(err -> assertStatus(err, HttpStatus.NOT_FOUND))
                .verify();
        verify(charItems).findByCharacterId(5L);
    }

    // ── unauthenticated principal fails closed ─────────────────────────────────

    @Test
    void nullJwtIsDeniedOnRead() {
        when(characters.findById(5L)).thenReturn(Mono.just(character(5L, OWNER)));

        StepVerifier.create(controller.get(5L, null))
                .expectErrorSatisfies(err -> assertForbidden(err))
                .verify();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void assertForbidden(Throwable err) {
        assertStatus(err, HttpStatus.FORBIDDEN);
    }

    private static void assertStatus(Throwable err, HttpStatus expected) {
        assertThat(err).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(expected);
    }

    private static Jwt jwt(String subject, String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of(roles))
                .build();
    }

    private static PlayerCharacter character(long id, String casdoorId) {
        PlayerCharacter c = new PlayerCharacter();
        c.setCasdoorId(casdoorId);
        setField(c, "id", id);
        return c;
    }

    private static CharacterQuest characterQuest(long characterId, long questId) {
        CharacterQuest cq = new CharacterQuest();
        setField(cq, "characterId", characterId);
        setField(cq, "questId", questId);
        return cq;
    }

    /** R2DBC entities populate ids/fields via reflection; these test stubs do
     *  the same to avoid needing setters that the production model omits. */
    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * No-op {@link GameStateService} subclass that only records how many SSE
     * stat-change broadcasts the controller attempted. The super constructor is
     * driven with harmless null/empty collaborators: it merely stores the
     * references (the only call it makes is {@code getIfAvailable()} on the
     * cache provider, which we return null from), and {@code @PostConstruct
     * init()} never runs outside a Spring context.
     */
    private static final class RecordingGameStateService extends GameStateService {
        int statChangeCount = 0;

        RecordingGameStateService() {
            super(null, null, null, null, nullCacheProvider(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public void notifyCharacterStatChange(CharacterDto dto) {
            statChangeCount++;
        }

        private static ObjectProvider<games.hugmesh.bff.service.PositionCacheService> nullCacheProvider() {
            return new ObjectProvider<>() {
                @Override public games.hugmesh.bff.service.PositionCacheService getObject(Object... args) { return null; }
                @Override public games.hugmesh.bff.service.PositionCacheService getObject() { return null; }
                @Override public games.hugmesh.bff.service.PositionCacheService getIfAvailable() { return null; }
                @Override public games.hugmesh.bff.service.PositionCacheService getIfUnique() { return null; }
            };
        }
    }

    /**
     * No-op {@link GameTimeService} subclass — the super constructor only stores
     * its two collaborators (we pass null), {@code @PostConstruct init()} never
     * fires outside Spring, and {@code currentTime()} returns null which the
     * dashboard tolerates.
     */
    private static final class StubGameTimeService extends GameTimeService {
        StubGameTimeService() {
            super(null, null);
        }

        @Override
        public games.hugmesh.bff.model.GameTimeDto currentTime() {
            return null;
        }
    }

    /**
     * No-op {@link ProgressionService} subclass — the super constructor only
     * stores its repositories (null here, never dereferenced without
     * {@code init}). The brand-aware dashboard now calls the 5-arg
     * {@code availableGamesWithLevels} (characterId, roles, userId, domain,
     * isSuperadmin); we override that overload directly and return empty so the
     * IDOR-gate assertions never reach the real GameAccessRepository-backed
     * catalog query. The 3-arg legacy overload is also stubbed for completeness.
     */
    private static final class StubProgressionService extends ProgressionService {
        StubProgressionService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public Flux<games.hugmesh.bff.model.GameWithChaptersDto> availableGamesWithLevels(
                Long characterId, List<String> roleNames, String userId) {
            return Flux.empty();
        }

        @Override
        public Flux<games.hugmesh.bff.model.GameWithChaptersDto> availableGamesWithLevels(
                Long characterId, List<String> roleNames, String userId,
                String callerDomain, boolean isSuperadmin) {
            return Flux.empty();
        }
    }
}

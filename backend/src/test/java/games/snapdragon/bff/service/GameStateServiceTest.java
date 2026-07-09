package games.snapdragon.bff.service;

import games.snapdragon.bff.model.CharacterPositionCache;
import games.snapdragon.bff.model.ControlEvent;
import games.snapdragon.bff.model.PlayerMovement;
import games.snapdragon.bff.model.PlayerPosition;
import games.snapdragon.bff.model.PlayerState;
import games.snapdragon.bff.repository.PlayerCharacterRepository;
import games.snapdragon.bff.repository.PlayerPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hydration + per-chapter position-isolation coverage for {@link GameStateService}.
 *
 * <p><strong>Why this file exists:</strong> PR #100 ("Add tests: wave2c coverage")
 * added zero test files despite its title/commit message. {@code
 * GameStateService} — the single largest file that PR touched (996 diff lines) —
 * had (and still has, before this file) NO dedicated test: the only place it is
 * ever {@code new}'d in the test tree is {@code SessionIsolationTest}, which
 * covers a completely different, orthogonal invariant (per-SESSION SSE fan-out
 * isolation). Neither the tiered load chain nor per-CHAPTER position isolation —
 * both called out explicitly in this class's own javadoc as load-bearing
 * correctness guarantees — had any coverage.
 *
 * <p>This file covers two things:
 * <ol>
 *   <li>{@link GameStateService#hydrate(long, long)}'s tiered load chain
 *       (Cassandra → Postgres → defaults, per the class javadoc's "Three-tier
 *       position storage" section) and {@link GameStateService#onChapterEnter}'s
 *       Cassandra-departure guard;</li>
 *   <li>The per-chapter position model (class javadoc: "A character traversing
 *       cave → field → cave is restored to the cave coordinates they last had on
 *       re-entry — the prior single-row layout clobbered position across
 *       chapters"). This is the regression Liquibase 027 / the {@code Key}-keyed
 *       in-memory map exist to prevent, pinned here via {@code applyControl}.</li>
 * </ol>
 *
 * <p><strong>Not covered here</strong> (a genuine gap still open for a future
 * pass — see this PR's description): {@code applyControl}'s Kafka analytics tee
 * ({@link games.snapdragon.bff.audit.GameEventPublisher}), the ClickHouse
 * cross-pod-recovery branch of the 4-arg {@code hydrate} overload
 * ({@link games.snapdragon.bff.audit.PlayerStateRecoveryService}), and
 * {@code emitWorldStateForChapter}'s Cassandra-roster merge with the in-memory
 * roster. {@code GameSessionService}, {@code GameUploadService}, {@code
 * AuthoritativeTickService}, and {@code ReferenceArenaSim} (the other classes
 * PR #100 touched) already have substantive dedicated test suites and were not
 * re-audited line-by-line here.
 */
class GameStateServiceTest {

    private static final long CHAR_ID = 5L;

    private PlayerPositionRepository positionRepo;
    private PositionCacheService positionCache;
    private GameStateService state;

    @BeforeEach
    void setUp() {
        PlayerCharacterRepository characterRepo = mock(PlayerCharacterRepository.class);
        positionRepo = mock(PlayerPositionRepository.class);
        GameTimeService gameTimeService = mock(GameTimeService.class);
        PgNotificationService pgNotify = mock(PgNotificationService.class);
        positionCache = mock(PositionCacheService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PositionCacheService> cacheProvider = mock(ObjectProvider.class);
        lenient().when(cacheProvider.getIfAvailable()).thenReturn(positionCache);

        // The PostConstruct init() and applyControl persistence path both hit
        // the repos; stub everything to harmless empties so the unit can
        // construct and run without a live DB (same idiom as SessionIsolationTest).
        lenient().when(characterRepo.findById((Long) any())).thenReturn(Mono.empty());
        lenient().when(positionRepo.findByCharacterIdAndChapterId(any(), any())).thenReturn(Mono.empty());
        lenient().when(positionRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(pgNotify.publish(anyString(), anyString())).thenReturn(Mono.empty());
        lenient().when(positionCache.findOne(anyLong(), anyLong())).thenReturn(Mono.empty());
        lenient().when(positionCache.markDeparted(anyLong(), anyLong())).thenReturn(Mono.empty());
        lenient().when(positionCache.charactersInChapter(anyLong())).thenReturn(Flux.empty());
        lenient().when(positionCache.upsert(anyLong(), anyLong(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyString(), any(Boolean.class), anyLong())).thenReturn(Mono.empty());

        state = new GameStateService(characterRepo, positionRepo, gameTimeService, pgNotify, cacheProvider,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    // ── tiered load chain: Cassandra → Postgres → defaults ─────────────────

    @Test
    void hydratePrefersCassandraOverPostgresWhenBothHaveARow() {
        long chapterId = 10L;
        when(positionCache.findOne(CHAR_ID, chapterId))
                .thenReturn(Mono.just(cacheRow(CHAR_ID, chapterId, 7, 8, 9)));
        when(positionRepo.findByCharacterIdAndChapterId(CHAR_ID, chapterId))
                .thenReturn(Mono.just(pgRow(CHAR_ID, chapterId, 1, 1, 1)));

        StepVerifier.create(state.hydrate(CHAR_ID, chapterId))
                .assertNext(s -> {
                    // Cassandra's row (7,8,9) wins over Postgres's (1,1,1) — proves
                    // the tier-1 (Cassandra) branch is preferred, not merely present.
                    assertThat(s.x()).isEqualTo(7);
                    assertThat(s.y()).isEqualTo(8);
                    assertThat(s.z()).isEqualTo(9);
                })
                .verifyComplete();
    }

    @Test
    void hydrateFallsThroughToPostgresWhenCassandraMisses() {
        long chapterId = 11L;
        when(positionCache.findOne(CHAR_ID, chapterId)).thenReturn(Mono.empty());
        when(positionRepo.findByCharacterIdAndChapterId(CHAR_ID, chapterId))
                .thenReturn(Mono.just(pgRow(CHAR_ID, chapterId, 3, 4, 5)));

        StepVerifier.create(state.hydrate(CHAR_ID, chapterId))
                .assertNext(s -> {
                    assertThat(s.x()).isEqualTo(3);
                    assertThat(s.y()).isEqualTo(4);
                    assertThat(s.z()).isEqualTo(5);
                })
                .verifyComplete();
    }

    @Test
    void hydrateDegradesToPostgresOnlyWhenCassandraIsNotConfigured() {
        // No Cassandra wired at all (ObjectProvider.getIfAvailable() == null) —
        // the "degrades gracefully to tiers 1 + 3" contract from the class javadoc.
        @SuppressWarnings("unchecked")
        ObjectProvider<PositionCacheService> noCacheProvider = mock(ObjectProvider.class);
        when(noCacheProvider.getIfAvailable()).thenReturn(null);
        PlayerCharacterRepository characterRepo = mock(PlayerCharacterRepository.class);
        lenient().when(characterRepo.findById((Long) any())).thenReturn(Mono.empty());
        GameTimeService gameTimeService = mock(GameTimeService.class);
        PgNotificationService pgNotify = mock(PgNotificationService.class);
        lenient().when(pgNotify.publish(anyString(), anyString())).thenReturn(Mono.empty());

        long chapterId = 12L;
        when(positionRepo.findByCharacterIdAndChapterId(CHAR_ID, chapterId))
                .thenReturn(Mono.just(pgRow(CHAR_ID, chapterId, 2, 2, 2)));

        GameStateService noCacheState = new GameStateService(characterRepo, positionRepo, gameTimeService, pgNotify,
                noCacheProvider, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());

        StepVerifier.create(noCacheState.hydrate(CHAR_ID, chapterId))
                .assertNext(s -> assertThat(s.x()).isEqualTo(2))
                .verifyComplete();
    }

    @Test
    void hydrateReturnsDefaultsWhenNeitherTierHasARow() {
        long chapterId = 13L;
        // Both tiers already default to Mono.empty() via the @BeforeEach stubs.
        StepVerifier.create(state.hydrate(CHAR_ID, chapterId))
                .assertNext(s -> {
                    assertThat(s.x()).isEqualTo(PlayerState.defaults().x());
                    assertThat(s.y()).isEqualTo(PlayerState.defaults().y());
                    assertThat(s.z()).isEqualTo(PlayerState.defaults().z());
                })
                .verifyComplete();
    }

    // ── per-chapter position isolation (class javadoc: cave → field → cave) ──

    /**
     * The regression this pins: before Liquibase 027 / the per-(characterId,
     * chapterId) {@code Key} map, position was a single row per character, so
     * moving in one chapter clobbered the position remembered for every other
     * chapter. A character bouncing cave → field → cave must resume the cave
     * walk exactly where it left off, unaffected by the field visit in between.
     */
    @Test
    void applyControlKeepsPositionsIsolatedAcrossChapters() {
        long cave = 100L;
        long field = 200L;
        List<PlayerMovement> seen = new CopyOnWriteArrayList<>();
        state.movementStream().subscribe(seen::add);

        // Move in the cave chapter.
        state.applyControl(new ControlEvent("MOVE_RIGHT", true, 1L, cave), CHAR_ID);
        // Move in the field chapter — a DIFFERENT chapter, same character.
        state.applyControl(new ControlEvent("MOVE_FORWARD", true, 2L, field), CHAR_ID);
        // Re-enter the cave and move again.
        state.applyControl(new ControlEvent("MOVE_RIGHT", true, 3L, cave), CHAR_ID);

        List<PlayerMovement> caveMoves = seen.stream().filter(m -> cave == m.chapterId()).toList();
        List<PlayerMovement> fieldMoves = seen.stream().filter(m -> field == m.chapterId()).toList();

        assertThat(caveMoves).hasSize(2);
        assertThat(fieldMoves).hasSize(1);

        double defaultX = PlayerState.defaults().x();
        double defaultY = PlayerState.defaults().y();
        double defaultZ = PlayerState.defaults().z();

        // First cave move: one MOVE_STEP right of spawn.
        assertThat(caveMoves.get(0).x()).isGreaterThan(defaultX);
        // Second cave move (after the field detour) continues from the FIRST
        // cave move's x — i.e. two steps right of spawn — rather than being
        // reset to spawn or clobbered by the field chapter's z-only movement.
        assertThat(caveMoves.get(1).x()).isGreaterThan(caveMoves.get(0).x());
        assertThat(caveMoves.get(1).y()).isEqualTo(caveMoves.get(0).y());
        assertThat(caveMoves.get(1).z()).isEqualTo(caveMoves.get(0).z());

        // The field move must not inherit the cave chapter's x-only movement.
        assertThat(fieldMoves.get(0).x()).isEqualTo(defaultX);
        assertThat(fieldMoves.get(0).y()).isEqualTo(defaultY);
        assertThat(fieldMoves.get(0).z()).isLessThan(defaultZ);
    }

    // ── onChapterEnter: markDeparted guard + re-hydrate ─────────────────────

    @Test
    void onChapterEnterMarksDepartedFromThePreviousChapterAndHydratesTheNew() {
        long from = 1L;
        long to = 2L;
        when(positionRepo.findByCharacterIdAndChapterId(CHAR_ID, to)).thenReturn(Mono.empty());

        state.onChapterEnter(CHAR_ID, from, to);

        verify(positionCache).markDeparted(CHAR_ID, from);
        // The destination chapter must have been hydrated (loadState consulted).
        verify(positionRepo).findByCharacterIdAndChapterId(CHAR_ID, to);
    }

    @Test
    void onChapterEnterIsANoOpDepartureWhenFromAndToAreTheSameChapter() {
        long chapterId = 3L;
        when(positionRepo.findByCharacterIdAndChapterId(CHAR_ID, chapterId)).thenReturn(Mono.empty());

        state.onChapterEnter(CHAR_ID, chapterId, chapterId);

        // Re-entering the SAME chapter (e.g. a reconnect) must not fire a
        // spurious Cassandra departure for a chapter the character never left.
        verify(positionCache, never()).markDeparted(anyLong(), anyLong());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static CharacterPositionCache cacheRow(long characterId, long chapterId,
                                                    double x, double y, double z) {
        return new CharacterPositionCache(characterId, chapterId, x, y, z, 0.0,
                "MOVE_FORWARD", true, "pod-test", Instant.now(), 1L);
    }

    /** {@link PlayerPosition} exposes public setters (unlike the reflection-only
     *  R2DBC entities elsewhere in this test tree), so no reflection needed here. */
    private static PlayerPosition pgRow(long characterId, long chapterId,
                                        double x, double y, double z) {
        PlayerPosition p = new PlayerPosition();
        p.setCharacterId(characterId);
        p.setChapterId(chapterId);
        p.setPosX(x);
        p.setPosY(y);
        p.setPosZ(z);
        return p;
    }
}

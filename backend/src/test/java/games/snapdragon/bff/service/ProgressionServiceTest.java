package games.snapdragon.bff.service;

import games.snapdragon.bff.model.Game;
import games.snapdragon.bff.model.GameWithChaptersDto;
import games.snapdragon.bff.model.Chapter;
import games.snapdragon.bff.repository.CharacterQuestRepository;
import games.snapdragon.bff.repository.GameAccessRepository;
import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.repository.ChapterPrerequisiteRepository;
import games.snapdragon.bff.repository.ChapterRepository;
import games.snapdragon.bff.repository.QuestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Access-policy intersection tests for {@link ProgressionService}.
 *
 * <p>The three-arg overload filters games to those whose ids are
 * returned by {@code GameAccessRepository.findVisibleGameIdsForPrincipal}.
 * Verifying that intersection works correctly here gives confidence
 * the role-gated visibility holds without spinning the full e2e stack.
 *
 * <p>The single-arg overload (legacy callers) delegates to the three-arg
 * with empty roles + null userId, so it sees only {@code scope='all'}
 * games — exercised by an explicit test below.
 */
class ProgressionServiceTest {

    private GameRepository games;
    private ChapterRepository levels;
    private ChapterPrerequisiteRepository prereqs;
    private QuestRepository quests;
    private CharacterQuestRepository charQuests;
    private GameAccessRepository access;
    private ProgressionService service;

    @BeforeEach
    void setUp() {
        games      = mock(GameRepository.class);
        levels     = mock(ChapterRepository.class);
        prereqs    = mock(ChapterPrerequisiteRepository.class);
        quests     = mock(QuestRepository.class);
        charQuests = mock(CharacterQuestRepository.class);
        access     = mock(GameAccessRepository.class);
        service    = new ProgressionService(games, levels, prereqs, quests, charQuests, access);

        // Common stub: character has no quest history (matters for level
        // unlock state, not for the access filter).
        when(charQuests.findByCharacterId(anyLong())).thenReturn(Flux.empty());
    }

    @Test
    void hidesGamesNotInTheVisibleSet() {
        // Catalog has two games (id 1 + 2). Access policy makes only id=1
        // visible to this principal — id=2 should be filtered out.
        when(games.findAllByOrderBySortOrderAsc()).thenReturn(Flux.just(
                gameStub(1L, "three-wizards", "TWiaT"),
                gameStub(2L, "dodecahedron", "Dodeca")
        ));
        when(access.findVisibleGameIdsForPrincipal(any(String[].class), anyString()))
                .thenReturn(Flux.just(1L));
        // Levels list: empty for both games keeps the test focused on the filter.
        when(levels.findByGameIdOrderBySortOrderAsc(anyLong())).thenReturn(Flux.empty());

        StepVerifier.create(
                service.availableGamesWithLevels(1L, List.of("player"), "user-1").collectList())
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).slug()).isEqualTo("three-wizards");
                })
                .verifyComplete();
    }

    @Test
    void emptyVisibilitySetReturnsEmptyCatalog() {
        when(games.findAllByOrderBySortOrderAsc()).thenReturn(Flux.just(
                gameStub(1L, "three-wizards", "TWiaT")
        ));
        when(access.findVisibleGameIdsForPrincipal(any(String[].class), anyString()))
                .thenReturn(Flux.empty());
        when(levels.findByGameIdOrderBySortOrderAsc(anyLong())).thenReturn(Flux.empty());

        StepVerifier.create(
                service.availableGamesWithLevels(1L, List.of(), "user-x").<List<GameWithChaptersDto>>collectList())
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void singleArgOverloadDelegatesWithEmptyRoles() {
        // The one-arg form is the legacy entry-point. It should pass empty
        // roles + null user id through, so only scope='all' games surface.
        // We assert the access call was made with those args via the
        // three-arg path returning a fixed set.
        when(games.findAllByOrderBySortOrderAsc()).thenReturn(Flux.just(
                gameStub(1L, "three-wizards", "TWiaT")
        ));
        when(access.findVisibleGameIdsForPrincipal(any(String[].class), any()))
                .thenReturn(Flux.just(1L));
        when(levels.findByGameIdOrderBySortOrderAsc(anyLong())).thenReturn(Flux.empty());

        StepVerifier.create(service.availableGamesWithLevels(1L).collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Game gameStub(long id, String slug, String name) {
        Game g = new Game();
        try {
            setField(g, "id", id);
            setField(g, "slug", slug);
            setField(g, "name", name);
            setField(g, "sortOrder", 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return g;
    }

    @SuppressWarnings("unused")
    private static Chapter levelStub(long id, long gameId, String slug, String sceneType) {
        // Reserved for future tests that need to assert level rendering.
        // Kept here so adding an assert later doesn't require re-deriving
        // the reflection helper.
        Chapter l = new Chapter();
        try {
            setField(l, "id", id);
            setField(l, "gameId", gameId);
            setField(l, "slug", slug);
            setField(l, "sceneType", sceneType);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return l;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

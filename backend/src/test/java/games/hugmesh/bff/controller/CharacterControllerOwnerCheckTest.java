package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.CharacterQuest;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OWASP IDOR fix #1: every CharacterController endpoint must verify that the
 * authenticated caller owns the target character before serving or mutating it.
 *
 * <ul>
 *   <li>USER-SITUATIONAL: list() scoped to the caller's casdoor_id.</li>
 *   <li>USER-SITUATIONAL / CROSS-USER DENY: get(), update(), completeQuest(),
 *       useFirstItem() — ALLOW for the owner, DENY (403) for any other caller.</li>
 *   <li>CROSS-USER DENY: seed / unowned characters (casdoor_id IS NULL) must
 *       always produce 403 regardless of who calls.</li>
 * </ul>
 */
class CharacterControllerOwnerCheckTest {

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

    private CharacterController ctl;

    @BeforeEach
    void setUp() {
        characters      = mock(PlayerCharacterRepository.class);
        gameStateService = mock(GameStateService.class);
        gameTimeService  = mock(GameTimeService.class);
        progression     = mock(ProgressionService.class);
        charQuests      = mock(CharacterQuestRepository.class);
        questRepo       = mock(QuestRepository.class);
        sessionRepo     = mock(PlayerSessionRepository.class);
        charSkills      = mock(CharacterSkillRepository.class);
        charItems       = mock(CharacterItemRepository.class);
        itemRepo        = mock(ItemRepository.class);

        ctl = new CharacterController(
                characters, gameStateService, gameTimeService, progression,
                charQuests, questRepo, sessionRepo, charSkills, charItems, itemRepo,
                new DomainRegistry("", ""));
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of("player"))
                .build();
    }

    private static PlayerCharacter character(Long id, String casdoorId) {
        PlayerCharacter c = new PlayerCharacter();
        try {
            var f = PlayerCharacter.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        c.setCasdoorId(casdoorId);
        return c;
    }

    private static games.hugmesh.bff.model.CharacterQuest questWithId(Long characterId, Long questId) {
        games.hugmesh.bff.model.CharacterQuest cq = new games.hugmesh.bff.model.CharacterQuest();
        try {
            var cid = games.hugmesh.bff.model.CharacterQuest.class.getDeclaredField("characterId");
            var qid = games.hugmesh.bff.model.CharacterQuest.class.getDeclaredField("questId");
            cid.setAccessible(true);
            qid.setAccessible(true);
            cid.set(cq, characterId);
            qid.set(cq, questId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return cq;
    }

    // ── list() ───────────────────────────────────────────────────────────────

    @Test
    void list_scopedToCallerCasdoorId() {
        // USER-SITUATIONAL: list() must only surface characters owned by the caller.
        PlayerCharacter c = character(1L, "caller-uid");
        when(characters.findByCasdoorId("caller-uid")).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.list(jwt("caller-uid")))
                .expectNextMatches(dto -> Long.valueOf(1L).equals(dto.id()))
                .verifyComplete();
    }

    // ── get() ────────────────────────────────────────────────────────────────

    @Test
    void get_owner_succeeds() {
        PlayerCharacter c = character(1L, "owner-uid");
        when(characters.findById(1L)).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.get(1L, jwt("owner-uid")))
                .expectNextMatches(dto -> Long.valueOf(1L).equals(dto.id()))
                .verifyComplete();
    }

    @Test
    void get_differentOwner_isForbidden() {
        // CROSS-USER DENY: JWT subject ≠ character's casdoor_id → 403.
        PlayerCharacter c = character(1L, "victim-uid");
        when(characters.findById(1L)).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.get(1L, jwt("attacker-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void get_nullCasdoorId_isForbidden() {
        // CROSS-USER DENY: seed / unowned characters (casdoor_id IS NULL in DB)
        // are never readable via the API — no caller can satisfy the owner check.
        PlayerCharacter c = character(42L, null);
        when(characters.findById(42L)).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.get(42L, jwt("any-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void get_notFound_is404() {
        when(characters.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(ctl.get(999L, jwt("any-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }

    // ── update() ─────────────────────────────────────────────────────────────

    @Test
    void update_owner_succeeds() {
        PlayerCharacter c = character(1L, "owner-uid");
        when(characters.findById(1L)).thenReturn(Mono.just(c));
        when(characters.save(any(PlayerCharacter.class))).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.update(1L, Map.of("currentHp", 50), jwt("owner-uid")))
                .expectNextMatches(dto -> Long.valueOf(1L).equals(dto.id()))
                .verifyComplete();
    }

    @Test
    void update_differentOwner_isForbidden() {
        // CROSS-USER DENY: stat patch rejected when caller ≠ character owner.
        PlayerCharacter c = character(1L, "victim-uid");
        when(characters.findById(1L)).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.update(1L, Map.of("currentHp", 50), jwt("attacker-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    // ── dashboard() ──────────────────────────────────────────────────────────

    @Test
    void dashboard_differentOwner_isForbidden() {
        // CROSS-USER DENY: dashboard snapshot must deny callers who don't own
        // the character. progression + charQuests are mocked because the
        // dashboard method constructs those Monos eagerly before reaching Mono.zip.
        PlayerCharacter victim = character(1L, "victim-uid");
        when(characters.findById(anyLong())).thenReturn(Mono.just(victim));
        when(progression.availableGamesWithLevels(anyLong(), anyList(), any(), any(), anyBoolean()))
                .thenReturn(Flux.empty());
        when(charQuests.findByCharacterId(anyLong())).thenReturn(Flux.empty());

        StepVerifier.create(ctl.dashboard(1L, jwt("attacker-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    // ── completeQuest() ──────────────────────────────────────────────────────

    @Test
    void completeQuest_owner_succeeds() {
        PlayerCharacter c = character(1L, "owner-uid");
        CharacterQuest cq = questWithId(1L, 10L);
        when(characters.findById(1L)).thenReturn(Mono.just(c));
        when(charQuests.findByCharacterIdAndQuestId(1L, 10L)).thenReturn(Mono.just(cq));
        when(charQuests.save(any())).thenReturn(Mono.just(cq));
        // rewardSkillId is null on the empty Quest → method returns cq immediately
        when(questRepo.findById(anyLong())).thenReturn(Mono.empty());

        StepVerifier.create(ctl.completeQuest(1L, 10L, jwt("owner-uid")))
                .expectNext(cq)
                .verifyComplete();
    }

    @Test
    void completeQuest_differentOwner_isForbidden() {
        // CROSS-USER DENY: quest completion rejected when caller ≠ character owner.
        PlayerCharacter c = character(1L, "victim-uid");
        when(characters.findById(1L)).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.completeQuest(1L, 10L, jwt("attacker-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    // ── useFirstItem() ───────────────────────────────────────────────────────

    @Test
    void useFirstItem_differentOwner_isForbidden() {
        // CROSS-USER DENY: item use rejected when caller ≠ character owner.
        PlayerCharacter c = character(1L, "victim-uid");
        when(characters.findById(1L)).thenReturn(Mono.just(c));

        StepVerifier.create(ctl.useFirstItem(1L, jwt("attacker-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void useFirstItem_notFound_is404() {
        when(characters.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(ctl.useFirstItem(999L, jwt("any-uid")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }
}

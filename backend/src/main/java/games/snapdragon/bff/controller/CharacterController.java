package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.CharacterDto;
import games.snapdragon.bff.model.CharacterQuest;
import games.snapdragon.bff.model.CharacterSkill;
import games.snapdragon.bff.model.DashboardDto;
import games.snapdragon.bff.model.GameWithLevelsDto;
import games.snapdragon.bff.model.PlayerSession;
import games.snapdragon.bff.model.QuestDto;
import games.snapdragon.bff.repository.CharacterItemRepository;
import games.snapdragon.bff.repository.CharacterQuestRepository;
import games.snapdragon.bff.repository.CharacterSkillRepository;
import games.snapdragon.bff.repository.ItemRepository;
import games.snapdragon.bff.repository.PlayerCharacterRepository;
import games.snapdragon.bff.repository.PlayerSessionRepository;
import games.snapdragon.bff.repository.QuestRepository;
import games.snapdragon.bff.service.GameStateService;
import games.snapdragon.bff.service.GameTimeService;
import games.snapdragon.bff.service.ProgressionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Tag(name = "Characters", description = "Player character CRUD. Requires authentication. Stat patches broadcast a character SSE event to all subscribers.")
@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final PlayerCharacterRepository characters;
    private final GameStateService gameStateService;
    private final GameTimeService gameTimeService;
    private final ProgressionService progression;
    private final CharacterQuestRepository charQuests;
    private final QuestRepository questRepo;
    private final PlayerSessionRepository sessionRepo;
    private final CharacterSkillRepository charSkills;
    private final CharacterItemRepository charItems;
    private final ItemRepository itemRepo;

    public CharacterController(PlayerCharacterRepository characters,
                               GameStateService gameStateService,
                               GameTimeService gameTimeService,
                               ProgressionService progression,
                               CharacterQuestRepository charQuests,
                               QuestRepository questRepo,
                               PlayerSessionRepository sessionRepo,
                               CharacterSkillRepository charSkills,
                               CharacterItemRepository charItems,
                               ItemRepository itemRepo) {
        this.characters       = characters;
        this.gameStateService = gameStateService;
        this.gameTimeService  = gameTimeService;
        this.progression      = progression;
        this.charQuests       = charQuests;
        this.questRepo        = questRepo;
        this.sessionRepo      = sessionRepo;
        this.charSkills       = charSkills;
        this.charItems        = charItems;
        this.itemRepo         = itemRepo;
    }

    @Operation(
        summary = "List all characters",
        description = """
            ```
            ──request──► characterRepo.findAll()
                          ──map(toDto)──►
             ──────────────●──●──●──●──|
                           CharacterDto…
            ```
            """)
    @GetMapping
    public Flux<CharacterDto> list() {
        return characters.findAll().map(CharacterDto::from);
    }

    @Operation(
        summary = "Get a single character",
        description = """
            ```
            ──request──► characterRepo.findById(id)
                               │
                     ┌─────────┴──────────┐
                     │ found              │ empty
                     ▼                    ▼
              map(toDto)         switchIfEmpty(404)
              ──────●─|                 ──✗
            ```
            """)
    @GetMapping("/{id}")
    public Mono<CharacterDto> get(@PathVariable Long id) {
        return characters.findById(id)
                .map(CharacterDto::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Operation(
        summary = "Patch character stats",
        description = """
            Applies a partial update to the character. Any subset of stat fields may be
            included. After save, broadcasts a `character` SSE event so all connected
            clients see the updated HP/MP/level immediately.

            Accepted patch keys: `currentHp`, `currentMp`, `maxHp`, `maxMp`, `level`,
            `xp`, `xpToNext`, `attack`, `defense`, `speed`, `magicPower`, `currentLevelId`.

            ```
            ──request──► characterRepo.findById(id)
                          ──●──► map(applyPatch)
                                  ──●──► characterRepo.save()
                                          ──●──► gameStateService
                                                 .notifyCharacterStatChange()
                                                 (emits character$ SSE event)
             ──────────────────────────────────────────────●─|
                                                          CharacterDto (updated)
            ```
            """)
    @PutMapping("/{id}")
    public Mono<CharacterDto> update(@PathVariable Long id, @RequestBody Map<String, Object> patch) {
        return characters.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(c -> {
                    if (patch.containsKey("currentHp"))
                        c.setCurrentHp(((Number) patch.get("currentHp")).intValue());
                    if (patch.containsKey("currentMp"))
                        c.setCurrentMp(((Number) patch.get("currentMp")).intValue());
                    if (patch.containsKey("level"))
                        c.setLevel(((Number) patch.get("level")).intValue());
                    if (patch.containsKey("xp"))
                        c.setXp(((Number) patch.get("xp")).intValue());
                    if (patch.containsKey("xpToNext"))
                        c.setXpToNext(((Number) patch.get("xpToNext")).intValue());
                    if (patch.containsKey("maxHp"))
                        c.setMaxHp(((Number) patch.get("maxHp")).intValue());
                    if (patch.containsKey("maxMp"))
                        c.setMaxMp(((Number) patch.get("maxMp")).intValue());
                    if (patch.containsKey("attack"))
                        c.setAttack(((Number) patch.get("attack")).intValue());
                    if (patch.containsKey("defense"))
                        c.setDefense(((Number) patch.get("defense")).intValue());
                    if (patch.containsKey("currentLevelId"))
                        c.setCurrentLevelId(patch.get("currentLevelId") == null ? null
                                : ((Number) patch.get("currentLevelId")).longValue());
                    return characters.save(c);
                })
                .doOnNext(c -> gameStateService.notifyCharacterStatChange(CharacterDto.from(c)))
                .map(CharacterDto::from);
    }

    @Operation(
        summary = "Passive dashboard snapshot",
        description = """
            Combined character + time + available-games + quests payload for the
            read-only dashboard component. Does NOT load the game canvas; the
            dashboard can subscribe to `/api/game/stream` and listen for
            `character` / `time` events for live updates on top of this snapshot.

            ```
            ──request──► zip(
                           characterRepo.findById(id),
                           progression.availableGames(id).collectList(),
                           charQuests.findByCharacterId(id)
                             .flatMap(cq -> questRepo.findById(cq.questId)
                               .map(q -> QuestDto.from(q, cq.status)))
                             .collectList(),
                           sessionRepo.findHistoryByCasdoorId(casdoorId).collectList())
                         )
                       ──map──► DashboardDto{
                                  character, gameTime, games, quests,
                                  totalPlayMinutes, totalSessions}
             ──────────────────────●─|
            ```
            """)
    @GetMapping("/{id}/dashboard")
    public Mono<DashboardDto> dashboard(
            @PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.oauth2.jwt.Jwt jwt) {
        Mono<CharacterDto> character = characters.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .map(CharacterDto::from);

        // Honour the per-game access policy: a player whose roles + user id
        // don't match any game_access row for a given game won't see it on
        // the dashboard either. Anonymous (no JWT) falls through to
        // scope='all' games only.
        java.util.List<String> roles = jwt == null
                ? java.util.List.of()
                : extractRoles(jwt);
        String userId = jwt == null ? null : jwt.getSubject();
        Mono<List<GameWithLevelsDto>> games = progression
                .availableGamesWithLevels(id, roles, userId).collectList();

        Mono<List<QuestDto>> quests = charQuests.findByCharacterId(id)
                .flatMap(cq -> questRepo.findById(cq.getQuestId())
                        .map(q -> QuestDto.from(q, cq.getStatus())))
                .collectList();

        Mono<List<PlayerSession>> sessions = characters.findById(id)
                .flatMapMany(pc -> pc.getCasdoorId() == null
                        ? Flux.empty()
                        : sessionRepo.findHistoryByCasdoorId(pc.getCasdoorId()))
                .collectList();

        return Mono.zip(character, games, quests, sessions)
                .map(t -> {
                    long totalMinutes = t.getT4().stream()
                            .mapToLong(s -> {
                                Instant end = s.getDisconnectedAt() != null
                                        ? s.getDisconnectedAt() : Instant.now();
                                return Math.max(Duration.between(s.getConnectedAt(), end).toMinutes(), 0);
                            }).sum();
                    return new DashboardDto(
                            t.getT1(),
                            gameTimeService.currentTime(),
                            t.getT2(),
                            t.getT3(),
                            (int) totalMinutes,
                            t.getT4().size());
                });
    }

    @Operation(
        summary = "Mark a quest complete for this character",
        description = """
            Sets `character_quests.status = 'completed'` and `completed_at = NOW()`
            for the given character+quest pair. Completing the last prerequisite
            quest unlocks the next level(s) for that character.

            ```
            ──request──► charQuests.findByCharacterIdAndQuestId(charId, questId)
                               │
                     ┌─────────┴──────────┐
                     │ found              │ empty
                     ▼                    ▼
              status='completed'     switchIfEmpty(404)
              completedAt=NOW()            ──✗
              charQuests.save()
                     │
              ──────●─|  CharacterQuest (updated)
            ```
            """)
    @PutMapping("/{id}/quests/{questId}/complete")
    public Mono<CharacterQuest> completeQuest(@PathVariable Long id, @PathVariable Long questId) {
        return charQuests.findByCharacterIdAndQuestId(id, questId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(cq -> {
                    cq.setStatus("completed");
                    cq.setCompletedAt(Instant.now());
                    return charQuests.save(cq);
                })
                // After marking complete, look up the quest's reward_skill_id
                // and grant it to the character if they don't already have it.
                .flatMap(cq -> questRepo.findById(cq.getQuestId())
                        .flatMap(quest -> {
                            Long rewardSkill = quest.getRewardSkillId();
                            if (rewardSkill == null) return Mono.just(cq);
                            return charSkills.findByCharacterId(id)
                                    .filter(cs -> rewardSkill.equals(cs.getSkillId()))
                                    .hasElements()
                                    .flatMap(alreadyHas -> {
                                        if (alreadyHas) return Mono.just(cq);
                                        CharacterSkill grant = new CharacterSkill();
                                        grant.setCharacterId(id);
                                        grant.setSkillId(rewardSkill);
                                        return charSkills.save(grant).thenReturn(cq);
                                    });
                        })
                        .defaultIfEmpty(cq));
    }

    @Operation(
        summary = "Use the character's first inventory item (E key / quick slot)",
        description = """
            Looks up the lowest-id item in `character_items` for this character,
            applies its effect (heal HP / restore MP / cure status), and decrements
            the quantity. Returns the updated character snapshot so the HUD reflects
            the new HP/MP. If the inventory is empty or the only items are
            non-consumable (weapons, armor), returns 404.

            ```
            ──request──► charItems.findByCharacterId(id)
                          ──filter qty>0, sort by id ──first──►
                            itemRepo.findById(item)
                              ──apply effect──► characters.save()
                              ──decrement quantity──► charItems.save()
             ──────────────────────────────────────●─|  CharacterDto
            ```
            """)
    @PostMapping("/{id}/use-first-item")
    public Mono<CharacterDto> useFirstItem(@PathVariable Long id) {
        return charItems.findByCharacterId(id)
                .filter(ci -> ci.getQuantity() > 0)
                .sort(java.util.Comparator.comparing(CharacterItem -> CharacterItem.getId() == null ? Long.MAX_VALUE : CharacterItem.getId()))
                .next()
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "no usable item")))
                .flatMap(ci -> itemRepo.findById(ci.getItemId())
                        .flatMap(item -> characters.findById(id).flatMap(c -> {
                            String effect = item.getEffectJson() == null ? "" : item.getEffectJson();
                            if (effect.contains("\"heal\"")) {
                                int heal = parseIntField(effect, "heal", 0);
                                c.setCurrentHp(Math.min(c.getMaxHp(), c.getCurrentHp() + heal));
                            }
                            if (effect.contains("\"mana\"")) {
                                int mana = parseIntField(effect, "mana", 0);
                                c.setCurrentMp(Math.min(c.getMaxMp(), c.getCurrentMp() + mana));
                            }
                            // Decrement quantity
                            ci.setQuantity(ci.getQuantity() - 1);
                            return charItems.save(ci)
                                    .then(characters.save(c))
                                    .doOnNext(saved -> gameStateService.notifyCharacterStatChange(CharacterDto.from(saved)))
                                    .map(CharacterDto::from);
                        })));
    }

    /** Tiny JSON int extractor — only handles `"key":<number>` patterns. */
    private static int parseIntField(String json, String key, int fallback) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return fallback;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end)); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static java.util.List<String> extractRoles(org.springframework.security.oauth2.jwt.Jwt jwt) {
        Object claim = jwt.getClaim("roles");
        if (claim instanceof java.util.List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(Object::toString).toList();
        }
        return java.util.List.of();
    }
}

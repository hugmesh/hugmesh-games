package com.threewizards.controller;

import com.threewizards.model.CharacterDto;
import com.threewizards.model.CharacterQuest;
import com.threewizards.model.DashboardDto;
import com.threewizards.model.GameWithLevelsDto;
import com.threewizards.model.PlayerSession;
import com.threewizards.model.QuestDto;
import com.threewizards.repository.CharacterQuestRepository;
import com.threewizards.repository.PlayerCharacterRepository;
import com.threewizards.repository.PlayerSessionRepository;
import com.threewizards.repository.QuestRepository;
import com.threewizards.service.GameStateService;
import com.threewizards.service.GameTimeService;
import com.threewizards.service.ProgressionService;
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

    public CharacterController(PlayerCharacterRepository characters,
                               GameStateService gameStateService,
                               GameTimeService gameTimeService,
                               ProgressionService progression,
                               CharacterQuestRepository charQuests,
                               QuestRepository questRepo,
                               PlayerSessionRepository sessionRepo) {
        this.characters       = characters;
        this.gameStateService = gameStateService;
        this.gameTimeService  = gameTimeService;
        this.progression      = progression;
        this.charQuests       = charQuests;
        this.questRepo        = questRepo;
        this.sessionRepo      = sessionRepo;
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
    public Mono<DashboardDto> dashboard(@PathVariable Long id) {
        Mono<CharacterDto> character = characters.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .map(CharacterDto::from);

        Mono<List<GameWithLevelsDto>> games = progression.availableGamesWithLevels(id).collectList();

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
                });
    }
}

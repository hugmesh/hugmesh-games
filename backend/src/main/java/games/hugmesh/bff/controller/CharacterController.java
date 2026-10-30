package games.hugmesh.bff.controller;

import games.hugmesh.bff.security.GamesAdminPolicy;

import games.hugmesh.bff.model.CharacterDto;
import games.hugmesh.bff.model.CharacterQuest;
import games.hugmesh.bff.model.CharacterSkill;
import games.hugmesh.bff.model.DashboardDto;
import games.hugmesh.bff.model.GameWithChaptersDto;
import games.hugmesh.bff.model.PlayerSession;
import games.hugmesh.bff.model.QuestDto;
import games.hugmesh.bff.repository.CharacterItemRepository;
import games.hugmesh.bff.repository.CharacterQuestRepository;
import games.hugmesh.bff.repository.CharacterSkillRepository;
import games.hugmesh.bff.repository.ItemRepository;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.PlayerSessionRepository;
import games.hugmesh.bff.repository.QuestRepository;
import games.hugmesh.bff.model.PlayerCharacter;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.GameStateService;
import games.hugmesh.bff.service.GameTimeService;
import games.hugmesh.bff.service.ProgressionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Tag(name = "Characters", description = "Player character CRUD. Requires authentication. Stat patches broadcast a character SSE event to all subscribers.")
@RestController
@RequestMapping("/api/characters")
@PreAuthorize("isAuthenticated()")
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
    private final games.hugmesh.bff.security.DomainRegistry domainRegistry;
    /** Who is a games admin (realm-aware; see GamesAdminPolicy). */
    private final GamesAdminPolicy gamesAdmin;

    public CharacterController(PlayerCharacterRepository characters,
                               GameStateService gameStateService,
                               GameTimeService gameTimeService,
                               ProgressionService progression,
                               CharacterQuestRepository charQuests,
                               QuestRepository questRepo,
                               PlayerSessionRepository sessionRepo,
                               CharacterSkillRepository charSkills,
                               CharacterItemRepository charItems,
                               ItemRepository itemRepo,
                               games.hugmesh.bff.security.DomainRegistry domainRegistry,
                               GamesAdminPolicy gamesAdmin) {
        this.gamesAdmin = gamesAdmin;
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
        this.domainRegistry   = domainRegistry;
    }

    /**
     * Object-level authorization gate (ASVS 4.1.3 / OWASP A01). Loads the
     * character by id and proves the caller is allowed to touch it before any
     * read or mutation runs: the JWT subject must equal the character's owning
     * {@code casdoorId}, OR the caller holds the {@code admin} role. Anything
     * else fails closed with 403 (404 first if the character doesn't exist, so
     * ownership can't be probed as an enumeration oracle). Every per-id
     * endpoint funnels through here so a single seam decides ownership; the
     * class-level {@code @PreAuthorize("isAuthenticated()")} guarantees a
     * non-null principal already reached this point.
     */
    private Mono<PlayerCharacter> ownedCharacter(Long id, Jwt jwt) {
        return characters.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(c -> {
                    String caller = jwt == null ? null : jwt.getSubject();
                    boolean owns = caller != null && Objects.equals(caller, c.getCasdoorId());
                    if (!owns && !gamesAdmin.isGamesAdmin(jwt)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "character does not belong to the caller"));
                    }
                    return Mono.just(c);
                });
    }

    @Operation(
        summary = "List the caller's characters",
        description = """
            Returns only the authenticated caller's own characters (matched on
            `casdoor_id == jwt.sub`). Games admins (`ROLE_GAMES_ADMIN`) get the full roster
            for moderation. A plain player can never enumerate other players'
            characters here.

            ```
            ──request──► admin?  characterRepo.findAll()
                          :       characterRepo.findByCasdoorId(jwt.sub)
                          ──map(toDto)──►
             ──────────────●──●──●──●──|
                           CharacterDto…
            ```
            """)
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Flux<CharacterDto> list(@AuthenticationPrincipal Jwt jwt) {
        if (gamesAdmin.isGamesAdmin(jwt)) {
            return characters.findAll().map(CharacterDto::from);
        }
        String caller = jwt == null ? null : jwt.getSubject();
        if (caller == null) {
            return Flux.empty();
        }
        return characters.findByCasdoorId(caller).flux().map(CharacterDto::from);
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
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public Mono<CharacterDto> get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return ownedCharacter(id, jwt)
                .map(CharacterDto::from);
    }

    @Operation(
        summary = "Patch character stats",
        description = """
            Applies a partial update to the character. Any subset of stat fields may be
            included. After save, broadcasts a `character` SSE event so all connected
            clients see the updated HP/MP/level immediately.

            Accepted patch keys: `currentHp`, `currentMp`, `maxHp`, `maxMp`, `level`,
            `xp`, `xpToNext`, `attack`, `defense`, `speed`, `magicPower`, `currentChapterId`.

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
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{id}")
    public Mono<CharacterDto> update(@PathVariable Long id,
                                     @RequestBody Map<String, Object> patch,
                                     @AuthenticationPrincipal Jwt jwt) {
        return ownedCharacter(id, jwt)
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
                    if (patch.containsKey("currentChapterId"))
                        c.setCurrentChapterId(patch.get("currentChapterId") == null ? null
                                : ((Number) patch.get("currentChapterId")).longValue());
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
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/dashboard")
    public Mono<DashboardDto> dashboard(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        // Object-level authz first: 404 if absent, 403 unless the caller owns
        // the character (or is admin). Only then assemble the snapshot — the
        // gate runs before any quest/session/co-player data is touched so a
        // direct id-path traversal can't deanonymize other players.
        return ownedCharacter(id, jwt).flatMap(owned -> dashboardFor(id, jwt));
    }

    private Mono<DashboardDto> dashboardFor(Long id, Jwt jwt) {
        Mono<CharacterDto> character = characters.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .map(CharacterDto::from);

        // Honour the per-game access policy: a player whose roles + user id
        // don't match any game_access row for a given game won't see it on
        // the dashboard either. Anonymous (no JWT) falls through to
        // scope='all' games only.
        java.util.List<String> roles = Roles.fromJwt(jwt);
        String userId = jwt == null ? null : jwt.getSubject();
        Mono<List<GameWithChaptersDto>> games = progression
                .availableGamesWithLevels(id, roles, userId,
                        Roles.domainOf(jwt, domainRegistry),
                        Roles.isSuperadmin(jwt)).collectList();

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
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{id}/quests/{questId}/complete")
    public Mono<CharacterQuest> completeQuest(@PathVariable Long id,
                                              @PathVariable Long questId,
                                              @AuthenticationPrincipal Jwt jwt) {
        return ownedCharacter(id, jwt)
                .flatMap(owned -> charQuests.findByCharacterIdAndQuestId(id, questId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND))))
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
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/use-first-item")
    public Mono<CharacterDto> useFirstItem(@PathVariable Long id,
                                           @AuthenticationPrincipal Jwt jwt) {
        return ownedCharacter(id, jwt)
                .flatMap(owned -> charItems.findByCharacterId(id)
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
                        }))));
    }

    private Mono<games.hugmesh.bff.model.PlayerCharacter> assertOwner(
            games.hugmesh.bff.model.PlayerCharacter c, Jwt jwt) {
        String owner = c.getCasdoorId();
        if (jwt == null || owner == null || !owner.equals(jwt.getSubject())) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
        }
        return Mono.just(c);
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

}

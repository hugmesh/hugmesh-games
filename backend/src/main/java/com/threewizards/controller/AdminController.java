package com.threewizards.controller;

import com.threewizards.model.AdminStatsDto;
import com.threewizards.model.CharacterDto;
import com.threewizards.model.SessionDto;
import com.threewizards.repository.PlayerCharacterRepository;
import com.threewizards.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Tag(name = "Admin", description = "Server-management endpoints. Requires ROLE_ADMIN (isAdmin claim in JWT).")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final PlayerCharacterRepository characters;
    private final SessionService sessionService;

    public AdminController(PlayerCharacterRepository characters, SessionService sessionService) {
        this.characters     = characters;
        this.sessionService = sessionService;
    }

    @Operation(
        summary = "List active sessions",
        description = """
            Returns all sessions currently in the `player_sessions` table.

            ```
            ──request──► sessionRepo.findAll()
             ──────────────●──●──●──|
                           SessionDto…
            ```
            """)
    @GetMapping("/sessions")
    public Flux<SessionDto> sessions() {
        return sessionService.activeSessions();
    }

    @Operation(
        summary = "Live admin SSE stream — active sessions, players, stats every 5 s",
        description = """
            Pushes the admin dashboard's live numbers continuously instead of
            requiring `/api/admin/stats` polling. Each tick emits one event per
            type so the dashboard can register `addEventListener` per channel.

            ```
            connect ─►  Flux.interval(0, 5s)
                         ──tick──tick──tick──⋯
                            │
                        per-tick: zip(
                            sessionService.computeStats(),
                            sessionService.activeSessions().collectList(),
                            characterRepo.findAll().collectList())
                            │
                          three SSE events:
                            event: stats     {AdminStatsDto}
                            event: sessions  [SessionDto…]
                            event: roster    [CharacterDto…]
            ```

            Cassandra-backed cross-pod synchronisation means even when the API
            scales horizontally the per-tick snapshot reflects every pod's
            session map, not just the one this request hit.
            """)
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream() {
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(5))
                .flatMap(tick -> Mono.zip(
                                sessionService.computeStats(),
                                sessionService.activeSessions().collectList(),
                                characters.findAll().map(CharacterDto::from).collectList()
                        )
                        .flatMapMany(t -> Flux.just(
                                ServerSentEvent.<Object>builder()
                                        .event("stats").data(t.getT1()).build(),
                                ServerSentEvent.<Object>builder()
                                        .event("sessions").data(t.getT2()).build(),
                                ServerSentEvent.<Object>builder()
                                        .event("roster").data(t.getT3()).build()
                        )));
    }

    @Operation(
        summary = "Aggregate usage statistics",
        description = """
            Computes the admin dashboard stats block from `player_sessions` and
            `player_characters`. Per-user rows report session count, total minutes,
            and online status so admins can see all users without loading any game.

            ```
            ──request──► zip(
                           sessionRepo.findAll().collectList(),
                           characterRepo.count())
                       ──map──► derive:
                                  totalUsers, totalSessions, currentlyActive,
                                  averageSessionMinutes, totalCharacters,
                                  perUser:[{casdoorId, characterName,
                                            sessionCount, totalMinutes, online}]
             ──────────────────────●─|
                                    AdminStatsDto
            ```
            """)
    @GetMapping("/stats")
    public Mono<AdminStatsDto> stats() {
        return sessionService.computeStats();
    }

    @Operation(
        summary = "Kick a player",
        description = """
            Force-removes a player's session regardless of controller status.
            If the kicked player held the controller role it becomes claimable.

            ```
            ──request──► sessionRepo.deleteByCasdoorId(casdoorId)
             ──────────────────────────────────────●─|  (empty body)
            ```
            """)
    @DeleteMapping("/sessions/{casdoorId}")
    public Mono<Void> kickPlayer(@PathVariable String casdoorId) {
        return sessionService.disconnect(casdoorId);
    }

    @Operation(
        summary = "List all characters (admin view)",
        description = """
            Returns every character in the system — no ownership filter applied.

            ```
            ──request──► characterRepo.findAll()
                          ──map(CharacterDto::from)──►
             ──────────────●──●──●──|
                           CharacterDto…
            ```
            """)
    @GetMapping("/characters")
    public Flux<CharacterDto> characters() {
        return characters.findAll().map(CharacterDto::from);
    }

    @Operation(
        summary = "Update a character (admin override)",
        description = """
            Same patch pipeline as the player endpoint but without ownership checks.
            Useful for seeding stats or correcting corrupted values.

            Accepted patch keys: `level`, `xp`, `xpToNext`, `maxHp`, `currentHp`,
            `maxMp`, `currentMp`, `attack`, `defense`, `speed`, `magicPower`.

            ```
            ──request──► characterRepo.findById(id)
                               │
                     ┌─────────┴──────────┐
                     │ found              │ empty
                     ▼                    ▼
              map(applyPatch)       switchIfEmpty(404)
              characterRepo.save()        ──✗
                     │
              ──────●─|  CharacterDto (updated)
            ```
            """)
    @PutMapping("/characters/{id}")
    public Mono<CharacterDto> updateCharacter(
            @PathVariable Long id,
            @RequestBody Map<String, Object> patch) {
        return characters.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(c -> {
                    if (patch.containsKey("level"))
                        c.setLevel(((Number) patch.get("level")).intValue());
                    if (patch.containsKey("xp"))
                        c.setXp(((Number) patch.get("xp")).intValue());
                    if (patch.containsKey("xpToNext"))
                        c.setXpToNext(((Number) patch.get("xpToNext")).intValue());
                    if (patch.containsKey("maxHp"))
                        c.setMaxHp(((Number) patch.get("maxHp")).intValue());
                    if (patch.containsKey("currentHp"))
                        c.setCurrentHp(((Number) patch.get("currentHp")).intValue());
                    if (patch.containsKey("maxMp"))
                        c.setMaxMp(((Number) patch.get("maxMp")).intValue());
                    if (patch.containsKey("currentMp"))
                        c.setCurrentMp(((Number) patch.get("currentMp")).intValue());
                    if (patch.containsKey("attack"))
                        c.setAttack(((Number) patch.get("attack")).intValue());
                    if (patch.containsKey("defense"))
                        c.setDefense(((Number) patch.get("defense")).intValue());
                    if (patch.containsKey("speed"))
                        c.setSpeed(((Number) patch.get("speed")).intValue());
                    if (patch.containsKey("magicPower"))
                        c.setMagicPower(((Number) patch.get("magicPower")).intValue());
                    return characters.save(c);
                })
                .map(CharacterDto::from);
    }
}

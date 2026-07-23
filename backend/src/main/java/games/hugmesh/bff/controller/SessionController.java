package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.SessionHistoryDto;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.service.GameStateService;
import games.hugmesh.bff.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Sessions", description = "Player session lifecycle — connect, claim controller, disconnect. Up to 3 concurrent players.")
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final GameStateService gameStateService;
    private final PlayerCharacterRepository characterRepo;

    public SessionController(SessionService sessionService,
                             GameStateService gameStateService,
                             PlayerCharacterRepository characterRepo) {
        this.sessionService = sessionService;
        this.gameStateService = gameStateService;
        this.characterRepo = characterRepo;
    }

    @Operation(
        summary = "Connect and receive a session token",
        description = """
            Creates or resumes a player session. The first player to connect is
            automatically granted the controller role. Rejects a fourth connection.

            ```
            ──request──► sessionRepo.countActiveSessions()
                               │
                     ┌─────────┴──────────┐
                     │ count < 3          │ count = 3
                     ▼                    ▼
              createOrResumeSession()  ──✗  409 Conflict
              (1st caller auto-claims
               controller role)
                     │
              ────────────────────────●─|
                {sessionToken, isController, activePlayers}
            ```
            """)
    @PostMapping("/connect")
    public Mono<Map<String, Object>> connect(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") Long characterId) {

        String casdoorId = jwt.getSubject();
        return sessionService.tryConnect(casdoorId, characterId)
                // Hydrate the connecting character's per-level state into
                // GameStateService so the next world-state SSE includes
                // them in the roster — without this hop, a player who
                // hasn't pressed any keys yet is invisible to the other
                // session members until they move (the in-memory state
                // map is populated lazily on first applyControl).
                .flatMap(token -> characterRepo.findById(characterId)
                        .flatMap(c -> {
                            long chapterId = c.getCurrentChapterId() != null ? c.getCurrentChapterId() : 1L;
                            return gameStateService.hydrate(characterId, chapterId)
                                    .thenReturn(token);
                        })
                        .defaultIfEmpty(token))
                .map(token -> Map.of(
                        "sessionToken", token,
                        "isController", sessionService.isController(casdoorId),
                        "activePlayers", sessionService.activeCount()
                ));
    }

    @Operation(
        summary = "Claim the controller role",
        description = """
            Attempts to acquire the exclusive controller role. Only one player at a time
            may hold it. Succeeds if no other session currently has `is_controller = true`.

            ```
            ──request──► sessionRepo.countByIsControllerTrue()
                               │
                     ┌─────────┴──────────┐
                     │ count = 0          │ count ≥ 1
                     ▼                    ▼
              setController(true)    ────────────────●─|
                     │                {granted: false}
              ────────────────────────●─|
              {granted: true}
            ```
            """)
    @PostMapping("/control")
    public Mono<Map<String, Object>> claimControl(@AuthenticationPrincipal Jwt jwt) {
        String casdoorId = jwt.getSubject();
        return sessionService.claimControl(casdoorId)
                .map(granted -> Map.of("granted", granted));
    }

    @Operation(
        summary = "Disconnect (graceful)",
        description = """
            Removes the caller's session. If they held the controller role,
            it becomes available for another player to claim.

            ```
            ──request──► sessionRepo.deleteByCasdoorId(sub)
             ──────────────────────────────────────●─|  (empty body)
            ```
            """)
    @DeleteMapping("/me")
    public Mono<Void> disconnect(@AuthenticationPrincipal Jwt jwt) {
        return sessionService.disconnect(jwt.getSubject());
    }

    @Operation(
        summary = "Session history with co-players",
        description = """
            Returns up to 50 past sessions for the authenticated user. Each row
            is enriched with the list of other users whose session window
            overlapped in time — useful for showing "you played with X" on the
            player dashboard.

            ```
            ──request──► sessionRepo.findHistoryByCasdoorId(sub)
                          ──flatMap(each session →
                              sessionRepo.findOverlappingSessions(sub, connected, disconnected)
                               ──flatMap(co →
                                   characterRepo.findById(co.characterId)
                                    .map(CoPlayerDto))
                               .collectList())
                          ──map(SessionHistoryDto)
             ──────────────●──●──●──|  (newest first, capped at 50)
            ```
            """)
    @GetMapping("/history")
    public Flux<SessionHistoryDto> history(@AuthenticationPrincipal Jwt jwt) {
        return sessionService.historyFor(jwt.getSubject());
    }
}

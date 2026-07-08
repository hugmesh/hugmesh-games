package games.snapdragon.bff.service;

import games.snapdragon.bff.model.AdminStatsDto;
import games.snapdragon.bff.model.PlayerSession;
import games.snapdragon.bff.model.SessionDto;
import games.snapdragon.bff.model.SessionHistoryDto;
import games.snapdragon.bff.repository.PlayerCharacterRepository;
import games.snapdragon.bff.repository.PlayerSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Single-player session lifecycle — connect, disconnect, controller claim, ping,
 * history, and admin statistics.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Manages {@link PlayerSession} rows in Postgres ({@code player_sessions} table)
 * and keeps a companion in-memory map ({@link #activeSessions}) that acts as the
 * hot-path presence index.  The in-memory map is the authoritative answer for "is
 * this player currently connected?" — Postgres rows are the durable history log.
 * Both views are written on connect and disconnect; the in-memory map is cleared on
 * disconnect so a pod restart begins with an empty present-player set, while
 * Postgres retains the full session history for analytics and the admin dashboard.
 *
 * <h2>Caller-role matrix</h2>
 *
 * <pre>
 *  Operation              │ Player │ Mod │ Admin │ Superadmin
 *  ───────────────────────┼────────┼─────┼───────┼───────────
 *  tryConnect             │  yes   │  -  │   -   │     -
 *  claimControl           │  yes   │  -  │   -   │     -
 *  disconnect             │  yes   │  -  │   -   │     -
 *  pingSession            │  yes   │  -  │   -   │     -
 *  historyFor             │  yes   │  -  │   -   │     -
 *  activeSessions / count │   -    │ yes │  yes  │    yes
 *  computeStats           │   -    │  -  │  yes  │    yes
 * </pre>
 *
 * <h2>Controller-claim model</h2>
 *
 * <p>Only one connected player may hold the "controller" role at any time, tracked
 * by {@link #currentController} (an {@link AtomicReference}).  The controller is
 * the player whose keyboard/gamepad input drives the Godot WASM engine.  Any active
 * player may call {@link #claimControl} to take over; the handover is atomic (no
 * two players can simultaneously believe they are the controller on this pod).
 *
 * <h2>Capacity limit</h2>
 *
 * <p>At most {@value #MAX_SESSIONS} players may be concurrently connected.
 * {@link #tryConnect} returns an error {@link Mono} (rather than throwing) when the
 * server is full and the caller is not already connected — callers should
 * {@code .onErrorResume} or let the reactive chain propagate a 429.
 *
 * <h2>Reactive model</h2>
 *
 * <p>All public methods that touch Postgres return a {@link Mono} or {@link Flux}.
 * Non-reactive state reads ({@link #isController}, {@link #hasController},
 * {@link #activeCount}) are safe to call from any thread — they access only
 * {@link ConcurrentHashMap} and {@link AtomicReference} without blocking.
 *
 * @see PlayerSessionRepository
 * @see PlayerCharacterRepository
 * @see SessionDto
 * @see AdminStatsDto
 * @see SessionHistoryDto
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** Maximum number of simultaneously connected players on this pod. */
    private static final int MAX_SESSIONS = 3;

    private final PlayerSessionRepository sessionRepo;
    private final PlayerCharacterRepository characterRepo;

    /**
     * In-memory presence index: Casdoor subject → live {@link SessionDto}.
     * Updated atomically on {@link #tryConnect} and {@link #disconnect}.
     * A pod restart clears this map; Postgres retains full history.
     */
    private final ConcurrentHashMap<String, SessionDto> activeSessions = new ConcurrentHashMap<>();

    /**
     * The Casdoor subject of the current controller, or {@code null} when no
     * player holds the role.  Mutated only via {@link AtomicReference} CAS so
     * concurrent claim and disconnect calls are race-free.
     */
    private final AtomicReference<String> currentController = new AtomicReference<>();

    public SessionService(PlayerSessionRepository sessionRepo, PlayerCharacterRepository characterRepo) {
        this.sessionRepo   = sessionRepo;
        this.characterRepo = characterRepo;
    }

    // -------------------------------------------------------------------------
    // Connect / disconnect lifecycle
    // -------------------------------------------------------------------------

    /**
     * Connect a player to the single-player session, issuing a fresh session token.
     *
     * <p>If the caller already has an open session row in Postgres (e.g. a prior
     * connect that never cleanly disconnected), the old row is closed
     * ({@code disconnected_at = now}) before the new row is created — keeping
     * the history append-only.  The caller's in-memory entry is replaced
     * unconditionally so a page-reload always gets a fresh token.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   sessionRepo.findByCasdoorId(casdoorId)          ← look up any open row
     *     → flatMap(prev → stamp disconnectedAt + save)  ← close it (history preserved)
     *     → then(sessionRepo.save(newSession))           ← insert fresh session row
     *     → doOnNext(put in activeSessions + log)        ← update in-memory index
     *     → map(PlayerSession::getSessionToken)          ← emit the new token
     * </pre>
     *
     * @param casdoorId   the Casdoor subject (unique user identifier) of the player
     * @param characterId the character the player is using for this session
     * @return a {@link Mono} emitting the new session token on success, or an error
     *         {@link Mono} with {@link IllegalStateException} when the server is full
     */
    public Mono<String> tryConnect(String casdoorId, Long characterId) {
        // Reject new connections when the server is full — allow reconnects
        // (player is already in the map) so a page reload doesn't lock the player out.
        if (activeSessions.size() >= MAX_SESSIONS && !activeSessions.containsKey(casdoorId)) {
            return Mono.error(new IllegalStateException("Server is full (max " + MAX_SESSIONS + " players)"));
        }

        // Session token surfaces in the URL the iframe carries on
        // every API call — derive via SHA3-256 + the SP800 DRBG so
        // every user-visible identifier in the BFF shares one mint.
        String token = games.snapdragon.bff.util.Crypto.mintUid();
        PlayerSession session = new PlayerSession();
        session.setSessionToken(token);
        session.setCasdoorId(casdoorId);
        session.setCharacterId(characterId);
        session.setController(false);
        session.setConnectedAt(Instant.now());
        session.setLastPing(Instant.now());

        // Close any lingering open session for this user (keep row for history),
        // then create a new one.
        return sessionRepo.findByCasdoorId(casdoorId)
                .flatMap(prev -> {
                    prev.setDisconnectedAt(Instant.now());
                    return sessionRepo.save(prev);
                })
                .then(sessionRepo.save(session))
                .doOnNext(saved -> {
                    activeSessions.put(casdoorId, SessionDto.from(saved));
                    log.info("Player connected: {} (sessions: {}/{})", casdoorId, activeSessions.size(), MAX_SESSIONS);
                })
                .map(PlayerSession::getSessionToken);
    }

    /**
     * Atomically transfer the controller role to {@code casdoorId}.
     *
     * <p>The caller must already be in {@link #activeSessions}; a player who is
     * not connected cannot claim the controller role.  The previous controller
     * receives no explicit notification here — the calling controller layer is
     * responsible for surfacing that information to the old holder if needed.
     *
     * @param casdoorId the Casdoor subject attempting to claim the controller role
     * @return a {@link Mono} emitting {@code true} when the claim succeeded, or
     *         {@code false} when the caller is not a connected player
     */
    public Mono<Boolean> claimControl(String casdoorId) {
        // Only connected players may claim the controller role.
        if (!activeSessions.containsKey(casdoorId)) {
            return Mono.just(false);
        }
        // Atomic swap — the old holder is displaced immediately.
        String prev = currentController.getAndSet(casdoorId);
        log.info("Controller changed: {} -> {}", prev, casdoorId);
        return Mono.just(true);
    }

    /**
     * Disconnect a player: remove from the in-memory map, release the controller
     * role if held, and stamp {@code disconnected_at} on the Postgres row.
     *
     * <p>The Postgres row is retained so the session appears in history queries.
     * The controller reference is cleared with a CAS to avoid accidentally
     * clearing a controller claim that already moved to a different player.
     *
     * @param casdoorId the Casdoor subject of the disconnecting player
     * @return a {@link Mono} that completes when the Postgres update is done
     */
    public Mono<Void> disconnect(String casdoorId) {
        // Drop from the in-memory presence index immediately.
        activeSessions.remove(casdoorId);
        // Release the controller role only if this player currently holds it.
        currentController.compareAndSet(casdoorId, null);
        log.info("Player disconnected: {} (sessions: {}/{})", casdoorId, activeSessions.size(), MAX_SESSIONS);
        // Mark the session ended; keep the row so session history stays intact.
        return sessionRepo.findByCasdoorId(casdoorId)
                .flatMap(s -> {
                    s.setDisconnectedAt(Instant.now());
                    return sessionRepo.save(s);
                })
                .then();
    }

    // -------------------------------------------------------------------------
    // Presence / controller queries (non-reactive, hot-path)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff {@code casdoorId} is the current controller.
     * Non-blocking — reads only {@link AtomicReference#get()}.
     *
     * @param casdoorId the Casdoor subject to test
     * @return {@code true} when this player currently holds the controller role
     */
    public boolean isController(String casdoorId) {
        return casdoorId != null && casdoorId.equals(currentController.get());
    }

    /**
     * Returns {@code true} iff any player currently holds the controller role.
     *
     * @return {@code true} when {@link #currentController} is non-null
     */
    public boolean hasController() {
        return currentController.get() != null;
    }

    /**
     * Snapshot of all currently connected players as {@link SessionDto} records.
     * Backed by the in-memory map — no database I/O.
     *
     * @return a {@link Flux} emitting one {@link SessionDto} per active player
     */
    public Flux<SessionDto> activeSessions() {
        return Flux.fromIterable(activeSessions.values());
    }

    /**
     * Count of currently connected players.  Non-blocking map size read.
     *
     * @return the number of players currently in {@link #activeSessions}
     */
    public int activeCount() {
        return activeSessions.size();
    }

    // -------------------------------------------------------------------------
    // Ping
    // -------------------------------------------------------------------------

    /**
     * Record a keep-alive ping for an active session.  Updates
     * {@code player_sessions.last_ping} in Postgres asynchronously (fire-and-forget
     * subscribe) so a caller's heartbeat does not add latency to the response path.
     * If the player is not in the in-memory map (already disconnected), the ping is
     * silently dropped.
     *
     * @param casdoorId the Casdoor subject of the pinging player
     */
    public void pingSession(String casdoorId) {
        SessionDto existing = activeSessions.get(casdoorId);
        if (existing != null) {
            // Fire-and-forget: update the DB timestamp but don't block the caller.
            sessionRepo.findByCasdoorId(casdoorId)
                    .doOnNext(s -> s.setLastPing(Instant.now()))
                    .flatMap(sessionRepo::save)
                    .subscribe();
        }
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    /**
     * Returns up to 50 recent sessions for a player, each enriched with the list
     * of other Casdoor users whose session overlapped in time with this one.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   sessionRepo.findHistoryByCasdoorId(casdoorId)      ← up to 50 past rows
     *     → flatMap(ps →
     *         sessionRepo.findOverlappingSessions(...)       ← concurrent others
     *           → flatMap(co → characterRepo.findById(...)) ← resolve name
     *           → defaultIfEmpty(CoPlayerDto with null name) ← handle unknown char
     *           → collectList()                              ← co-players list
     *           → map(coPlayers → SessionHistoryDto(...))   ← assemble DTO
     *       )
     * </pre>
     *
     * @param casdoorId the Casdoor subject whose history is requested
     * @return a {@link Flux} of {@link SessionHistoryDto} records, newest first
     */
    public Flux<SessionHistoryDto> historyFor(String casdoorId) {
        return sessionRepo.findHistoryByCasdoorId(casdoorId)
                .flatMap(ps -> sessionRepo.findOverlappingSessions(
                                casdoorId, ps.getConnectedAt(), ps.getDisconnectedAt())
                        .flatMap(co -> characterRepo.findById(co.getCharacterId() == null ? -1L : co.getCharacterId())
                                .map(pc -> new SessionHistoryDto.CoPlayerDto(co.getCasdoorId(), pc.getName()))
                                .defaultIfEmpty(new SessionHistoryDto.CoPlayerDto(co.getCasdoorId(), null)))
                        .collectList()
                        .map(coPlayers -> {
                            // Treat still-active sessions (no disconnectedAt) as ending now.
                            Instant end = ps.getDisconnectedAt() != null ? ps.getDisconnectedAt() : Instant.now();
                            long minutes = Duration.between(ps.getConnectedAt(), end).toMinutes();
                            return new SessionHistoryDto(
                                    ps.getId(), ps.getSessionToken(), ps.getCharacterId(),
                                    ps.isController(), ps.getConnectedAt(), ps.getDisconnectedAt(),
                                    Math.max(minutes, 0), coPlayers);
                        }));
    }

    // -------------------------------------------------------------------------
    // Admin statistics
    // -------------------------------------------------------------------------

    /**
     * Aggregate usage statistics for the admin dashboard.
     *
     * <p>Loads the full {@code player_sessions} table and all character rows in
     * parallel, then computes per-user session counts, total minutes, and online
     * status from the in-memory map.  Designed for low-frequency admin calls —
     * not for hot-path use.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   Mono.zip(
     *     sessionRepo.findAll().collectList(),   ← all session rows
     *     characterRepo.count()                  ← total character count
     *   )
     *     → flatMap(tuple →
     *         compute totals from session list (pure CPU)
     *         → Flux.fromIterable(byUser.entrySet())
     *             → flatMap(entry →
     *                 characterRepo.findById(charId)          ← resolve name
     *                 → defaultIfEmpty(PerUserStatsDto(null)) ← unknown char
     *               )
     *             → collectList()
     *             → map(perUser → AdminStatsDto(...))
     *       )
     * </pre>
     *
     * @return a {@link Mono} emitting one {@link AdminStatsDto} with aggregated
     *         counts, averages, and per-user breakdowns
     */
    public Mono<AdminStatsDto> computeStats() {
        Mono<List<PlayerSession>> allSessionsMono = sessionRepo.findAll().collectList();
        Mono<Long> totalCharacters = characterRepo.count();

        return Mono.zip(allSessionsMono, totalCharacters).flatMap(tuple -> {
            List<PlayerSession> all = tuple.getT1();
            long totalCharactersL = tuple.getT2();

            // Pure-CPU aggregation over the in-memory session list.
            long totalSessions = all.size();
            long currentlyActive = all.stream().filter(s -> s.getDisconnectedAt() == null).count();
            long totalUsers = all.stream().map(PlayerSession::getCasdoorId).distinct().count();

            // Average session length in minutes across all sessions (active sessions
            // use "now" as their end time so they contribute to the average correctly).
            double avgMinutes = all.stream()
                    .mapToLong(s -> {
                        Instant end = s.getDisconnectedAt() != null ? s.getDisconnectedAt() : Instant.now();
                        return Duration.between(s.getConnectedAt(), end).toMinutes();
                    })
                    .average().orElse(0.0);

            // Group sessions by user so we can compute per-user totals.
            Map<String, List<PlayerSession>> byUser = all.stream()
                    .collect(Collectors.groupingBy(PlayerSession::getCasdoorId));

            // Resolve character names reactively — one DB lookup per unique user.
            return Flux.fromIterable(byUser.entrySet())
                    .flatMap(entry -> {
                        String casdoorId = entry.getKey();
                        List<PlayerSession> userSessions = entry.getValue();
                        long sessionCount = userSessions.size();
                        // Sum minutes across all sessions for this user; clamp negatives to 0.
                        long minutes = userSessions.stream()
                                .mapToLong(s -> {
                                    Instant end = s.getDisconnectedAt() != null ? s.getDisconnectedAt() : Instant.now();
                                    return Math.max(Duration.between(s.getConnectedAt(), end).toMinutes(), 0);
                                })
                                .sum();
                        // Check the in-memory map for live presence — DB alone can't tell us.
                        boolean online = activeSessions.containsKey(casdoorId);
                        // Use the most recent session's character id for the name lookup.
                        Long charId = userSessions.get(0).getCharacterId();
                        Mono<games.snapdragon.bff.model.PlayerCharacter> charMono = charId != null
                                ? characterRepo.findById(charId)
                                : Mono.empty();
                        return charMono
                                .map(pc -> new AdminStatsDto.PerUserStatsDto(
                                        casdoorId, pc.getName(), sessionCount, minutes, online))
                                .defaultIfEmpty(new AdminStatsDto.PerUserStatsDto(
                                        casdoorId, null, sessionCount, minutes, online));
                    })
                    .collectList()
                    // Round average to 1 decimal place for the dashboard display.
                    .map(perUser -> new AdminStatsDto(
                            totalUsers, totalSessions, currentlyActive,
                            Math.round(avgMinutes * 10.0) / 10.0,
                            totalCharactersL, perUser));
        });
    }
}

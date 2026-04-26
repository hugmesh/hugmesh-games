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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int MAX_SESSIONS = 3;

    private final PlayerSessionRepository sessionRepo;
    private final PlayerCharacterRepository characterRepo;
    // In-memory cache of active sessions keyed by casdoorId
    private final ConcurrentHashMap<String, SessionDto> activeSessions = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentController = new AtomicReference<>();

    public SessionService(PlayerSessionRepository sessionRepo, PlayerCharacterRepository characterRepo) {
        this.sessionRepo   = sessionRepo;
        this.characterRepo = characterRepo;
    }

    public Mono<String> tryConnect(String casdoorId, Long characterId) {
        if (activeSessions.size() >= MAX_SESSIONS && !activeSessions.containsKey(casdoorId)) {
            return Mono.error(new IllegalStateException("Server is full (max " + MAX_SESSIONS + " players)"));
        }

        String token = UUID.randomUUID().toString();
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

    public Mono<Boolean> claimControl(String casdoorId) {
        if (!activeSessions.containsKey(casdoorId)) {
            return Mono.just(false);
        }
        String prev = currentController.getAndSet(casdoorId);
        log.info("Controller changed: {} -> {}", prev, casdoorId);
        return Mono.just(true);
    }

    public Mono<Void> disconnect(String casdoorId) {
        activeSessions.remove(casdoorId);
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

    public boolean isController(String casdoorId) {
        return casdoorId != null && casdoorId.equals(currentController.get());
    }

    public boolean hasController() {
        return currentController.get() != null;
    }

    public Flux<SessionDto> activeSessions() {
        return Flux.fromIterable(activeSessions.values());
    }

    public int activeCount() {
        return activeSessions.size();
    }

    public void pingSession(String casdoorId) {
        SessionDto existing = activeSessions.get(casdoorId);
        if (existing != null) {
            sessionRepo.findByCasdoorId(casdoorId)
                    .doOnNext(s -> s.setLastPing(Instant.now()))
                    .flatMap(sessionRepo::save)
                    .subscribe();
        }
    }

    /**
     * Returns up to 50 recent sessions for a player, each enriched with the list
     * of other casdoor users whose session overlapped in time with this one.
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
                            Instant end = ps.getDisconnectedAt() != null ? ps.getDisconnectedAt() : Instant.now();
                            long minutes = Duration.between(ps.getConnectedAt(), end).toMinutes();
                            return new SessionHistoryDto(
                                    ps.getId(), ps.getSessionToken(), ps.getCharacterId(),
                                    ps.isController(), ps.getConnectedAt(), ps.getDisconnectedAt(),
                                    Math.max(minutes, 0), coPlayers);
                        }));
    }

    /**
     * Aggregate usage statistics for the admin dashboard.
     */
    public Mono<AdminStatsDto> computeStats() {
        Mono<List<PlayerSession>> allSessionsMono = sessionRepo.findAll().collectList();
        Mono<Long> totalCharacters = characterRepo.count();

        return Mono.zip(allSessionsMono, totalCharacters).flatMap(tuple -> {
            List<PlayerSession> all = tuple.getT1();
            long totalCharactersL = tuple.getT2();

            long totalSessions = all.size();
            long currentlyActive = all.stream().filter(s -> s.getDisconnectedAt() == null).count();
            long totalUsers = all.stream().map(PlayerSession::getCasdoorId).distinct().count();

            double avgMinutes = all.stream()
                    .mapToLong(s -> {
                        Instant end = s.getDisconnectedAt() != null ? s.getDisconnectedAt() : Instant.now();
                        return Duration.between(s.getConnectedAt(), end).toMinutes();
                    })
                    .average().orElse(0.0);

            Map<String, List<PlayerSession>> byUser = all.stream()
                    .collect(Collectors.groupingBy(PlayerSession::getCasdoorId));

            return Flux.fromIterable(byUser.entrySet())
                    .flatMap(entry -> {
                        String casdoorId = entry.getKey();
                        List<PlayerSession> userSessions = entry.getValue();
                        long sessionCount = userSessions.size();
                        long minutes = userSessions.stream()
                                .mapToLong(s -> {
                                    Instant end = s.getDisconnectedAt() != null ? s.getDisconnectedAt() : Instant.now();
                                    return Math.max(Duration.between(s.getConnectedAt(), end).toMinutes(), 0);
                                })
                                .sum();
                        boolean online = activeSessions.containsKey(casdoorId);
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
                    .map(perUser -> new AdminStatsDto(
                            totalUsers, totalSessions, currentlyActive,
                            Math.round(avgMinutes * 10.0) / 10.0,
                            totalCharactersL, perUser));
        });
    }
}

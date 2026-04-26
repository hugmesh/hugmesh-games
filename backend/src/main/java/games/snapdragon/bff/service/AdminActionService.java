package games.snapdragon.bff.service;

import games.snapdragon.bff.model.AdminAction;
import games.snapdragon.bff.repository.AdminActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;

/**
 * Moderator-action ledger + live broadcast.
 *
 * <p>Kicks and bans are stored append-only in {@code admin_actions}.
 * Each write is also pushed onto the in-process
 * {@link #adverseSink} so the SSE admin stream and the
 * targeted-player tabs (via the host shell's BroadcastChannel relay)
 * see the action without polling.
 *
 * <p>Effective state for a player is the LATEST row keyed by
 * {@code target_casdoor_id}:
 * <ul>
 *   <li>{@code ban} active and not expired → player is banned;</li>
 *   <li>{@code unban} → player is allowed back;</li>
 *   <li>{@code kick} is one-shot — it doesn't stick, just closes the
 *       active session window.</li>
 * </ul>
 */
@Service
public class AdminActionService {

    private static final Logger log = LoggerFactory.getLogger(AdminActionService.class);

    private final AdminActionRepository repo;

    /** Live admin-action broadcast — ban/kick/unban events. autoCancel
     *  false so the SSE consumer reconnects don't kill the producer. */
    private final Sinks.Many<AdminAction> adverseSink =
            Sinks.many().multicast().onBackpressureBuffer(64, false);

    public AdminActionService(AdminActionRepository repo) {
        this.repo = repo;
    }

    public Flux<AdminAction> stream() {
        return adverseSink.asFlux();
    }

    public Flux<AdminAction> historyFor(String targetCasdoorId) {
        return repo.findByTargetCasdoorIdOrderByCreatedAtDesc(targetCasdoorId);
    }

    /** Latest row for the given player — used by the banned-page
     *  loader and the security filter's ban check. */
    public Mono<AdminAction> latestFor(String targetCasdoorId) {
        return repo.findFirstByTargetCasdoorIdOrderByCreatedAtDesc(targetCasdoorId);
    }

    public Mono<AdminAction> kick(String target, String actor, String message) {
        return record(target, actor, AdminAction.KICK, message, null);
    }

    public Mono<AdminAction> ban(String target, String actor, String message, Instant expiresAt) {
        return record(target, actor, AdminAction.BAN, message, expiresAt);
    }

    public Mono<AdminAction> unban(String target, String actor, String message) {
        return record(target, actor, AdminAction.UNBAN, message, null);
    }

    /**
     * True iff the latest action is an active (not-yet-expired) ban
     * AND not superseded by an unban. Returns false fast for empty
     * history.
     */
    public Mono<Boolean> isBanned(String targetCasdoorId) {
        return latestFor(targetCasdoorId)
                .map(a -> {
                    if (!AdminAction.BAN.equals(a.getAction())) return false;
                    if (a.getExpiresAt() == null) return true;
                    return a.getExpiresAt().isAfter(Instant.now());
                })
                .defaultIfEmpty(false);
    }

    private Mono<AdminAction> record(String target, String actor,
                                     String actionType, String message,
                                     Instant expiresAt) {
        AdminAction row = new AdminAction();
        row.setTargetCasdoorId(target);
        row.setActorCasdoorId(actor);
        row.setAction(actionType);
        row.setMessage(message);
        row.setExpiresAt(expiresAt);
        row.setCreatedAt(Instant.now());
        return repo.save(row).doOnNext(saved -> {
            log.info("[admin-action] {} {} by {}{}",
                    actionType, target, actor,
                    message == null ? "" : " — " + message);
            adverseSink.tryEmitNext(saved);
        });
    }
}

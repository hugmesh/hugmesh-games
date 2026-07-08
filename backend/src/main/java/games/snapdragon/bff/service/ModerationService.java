package games.snapdragon.bff.service;

import games.snapdragon.bff.model.AdminAction;
import games.snapdragon.bff.repository.AdminActionRepository;
import games.snapdragon.bff.repository.GameSessionRepository;
import games.snapdragon.bff.repository.ModGameScopeRepository;
import games.snapdragon.bff.repository.SessionMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Moderation actions scoped per-game.
 *
 * <p>Casdoor's {@code mod} role grants the capability; the
 * {@link ModGameScopeRepository} rows decide *which games* a moderator
 * may act in. Every action checks both — an unscoped mod hitting the
 * endpoint receives 403.
 *
 * <p>Two action verbs:
 * <ul>
 *   <li>{@code stopSession} — closes a session the mod is not a member of.
 *       Distinct from {@link GameSessionService#close} which is restricted
 *       to the session creator.</li>
 *   <li>{@code kickPlayer} — stamps {@code session_members.left_at} for a
 *       single player; if it leaves zero members, the session auto-closes.</li>
 * </ul>
 *
 * <p>Both write to {@code admin_actions} so the audit ledger captures the
 * moderator who took the action and any human-readable reason.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final GameSessionRepository    sessions;
    private final SessionMemberRepository  members;
    private final ModGameScopeRepository   scopes;
    private final AdminActionRepository    actions;
    private final GameSessionService       sessionService;

    public ModerationService(GameSessionRepository sessions,
                             SessionMemberRepository members,
                             ModGameScopeRepository scopes,
                             AdminActionRepository actions,
                             GameSessionService sessionService) {
        this.sessions       = sessions;
        this.members        = members;
        this.scopes         = scopes;
        this.actions        = actions;
        this.sessionService = sessionService;
    }

    /**
     * Force-close a session. Authorizes the caller as a mod scoped to
     * the session's {@code game_slug}, then delegates to
     * {@link GameSessionService#close}. Audit row written either way.
     */
    public Mono<Void> stopSession(String modCasdoorId, String sessionId, String reason) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")))
                .flatMap(s -> requireScope(modCasdoorId, s.getGameSlug())
                        .then(sessionService.close(sessionId))
                        .then(audit(modCasdoorId, "stop-session", sessionId, /* targetCasdoorId */ null, reason)));
    }

    /**
     * Kick one player out of a session. Marks their session_members row
     * with {@code left_at}; if no active members remain after the kick,
     * the session auto-closes via the leave path.
     */
    public Mono<Void> kickPlayer(String modCasdoorId, String sessionId, String targetCasdoorId, String reason) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")))
                .flatMap(s -> requireScope(modCasdoorId, s.getGameSlug())
                        .then(members.findActive(sessionId, targetCasdoorId))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "target is not an active member")))
                        .flatMap(m -> {
                            m.setLeftAt(Instant.now());
                            return members.save(m).then();
                        })
                        // If they were the last active member, leave-path auto-closes the session.
                        .then(sessionService.leave(sessionId, targetCasdoorId).onErrorResume(e -> Mono.empty()))
                        .then(audit(modCasdoorId, "kick", sessionId, targetCasdoorId, reason)));
    }

    /**
     * Authorize: caller must hold an active mod_game_scopes row for
     * {@code gameSlug}. Casdoor's role membership is checked at the
     * controller level via {@code @PreAuthorize}; this is the per-game
     * scoping check.
     */
    public Mono<Void> requireScope(String modCasdoorId, String gameSlug) {
        return scopes.findActive(modCasdoorId, gameSlug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "mod is not scoped to game " + gameSlug)))
                .then();
    }

    private Mono<Void> audit(String actor, String action, String sessionId, String target, String reason) {
        AdminAction row = new AdminAction();
        row.setActorCasdoorId(actor);
        // The admin_actions check constraint pins kick/ban/unban; mod
        // verbs map to "kick" (the closest existing semantic) with the
        // session id + actual verb in the message field for audit.
        String dbVerb = "stop-session".equals(action) ? AdminAction.KICK : AdminAction.KICK;
        row.setAction(dbVerb);
        row.setTargetCasdoorId(target);
        String breadcrumb = String.format("[%s sessionId=%s]%s", action, sessionId,
                reason == null ? "" : " " + reason);
        row.setMessage(breadcrumb);
        row.setCreatedAt(Instant.now());
        log.info("Mod action by {}: {} on session {} target={} reason={}",
                actor, action, sessionId, target, reason);
        return actions.save(row).then();
    }
}

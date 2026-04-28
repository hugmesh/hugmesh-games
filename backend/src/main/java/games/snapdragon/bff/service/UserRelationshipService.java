package games.snapdragon.bff.service;

import games.snapdragon.bff.model.UserRelationship;
import games.snapdragon.bff.repository.PublisherGameScopeRepository;
import games.snapdragon.bff.repository.UserRelationshipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Friend graph + blocklist orchestration.
 *
 * <p>Only the caller's own outgoing edges are mutable. The query helpers
 * are used by GameSessionService to filter the lobby list (friends-only
 * sessions hide from non-friends, blocked users' sessions hide entirely).
 *
 * <p>Mod/publisher/admin roles cannot be blocked at the data layer — the
 * UI surfaces "block" only against players. Non-player blocks are
 * silently dropped here so a malicious client posting raw payloads
 * doesn't get to silence moderators.
 */
@Service
public class UserRelationshipService {

    private final UserRelationshipRepository  repo;
    private final PublisherGameScopeRepository publisherScopes;

    public UserRelationshipService(UserRelationshipRepository repo,
                                   PublisherGameScopeRepository publisherScopes) {
        this.repo            = repo;
        this.publisherScopes = publisherScopes;
    }

    /** Send a friend request, or auto-accept if the other party has
     *  already sent one to us. Idempotent: re-sending an existing
     *  request is a no-op. */
    public Mono<UserRelationship> requestFriend(String user, String other) {
        if (user == null || user.isBlank() || other == null || other.isBlank() || user.equals(other)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid pair"));
        }
        return repo.findEdge(other, user)
                .flatMap(reverse -> {
                    if (UserRelationship.FRIEND_REQUEST.equals(reverse.getRelationship())) {
                        // Auto-accept: flip the existing reverse-edge to friend
                        // and create a forward friend edge.
                        reverse.setRelationship(UserRelationship.FRIEND);
                        reverse.setUpdatedAt(Instant.now());
                        return repo.save(reverse).then(upsertEdge(user, other, UserRelationship.FRIEND));
                    }
                    if (UserRelationship.BLOCKED.equals(reverse.getRelationship())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "blocked"));
                    }
                    if (UserRelationship.FRIEND.equals(reverse.getRelationship())) {
                        // Other side has us as friend already — make ours friend too.
                        return upsertEdge(user, other, UserRelationship.FRIEND);
                    }
                    return upsertEdge(user, other, UserRelationship.FRIEND_REQUEST);
                })
                .switchIfEmpty(upsertEdge(user, other, UserRelationship.FRIEND_REQUEST));
    }

    /** Accept an inbound friend request: flip both sides to 'friend'. */
    public Mono<UserRelationship> acceptFriend(String user, String other) {
        return repo.findEdge(other, user)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "no pending request")))
                .flatMap(req -> {
                    if (!UserRelationship.FRIEND_REQUEST.equals(req.getRelationship())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "not a pending request"));
                    }
                    req.setRelationship(UserRelationship.FRIEND);
                    req.setUpdatedAt(Instant.now());
                    return repo.save(req).then(upsertEdge(user, other, UserRelationship.FRIEND));
                });
    }

    /** Decline an inbound request OR remove an existing friendship. */
    public Mono<Void> removeFriend(String user, String other) {
        return repo.findEdge(user, other).flatMap(repo::delete).then(
                repo.findEdge(other, user).flatMap(repo::delete));
    }

    /** Block another user. Mod/publisher/admin roles are not blockable. */
    public Mono<UserRelationship> block(String user, String other) {
        if (user.equals(other)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot block self"));
        }
        return publisherScopes.findActiveByCasdoor(other).hasElements()
                .flatMap(isPublisher -> {
                    if (Boolean.TRUE.equals(isPublisher)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "cannot block publishers"));
                    }
                    // Admin gating happens at the controller layer via Casdoor
                    // role claims — the BFF doesn't keep an admin table.
                    // Removing any existing friend edges so the block takes precedence.
                    return removeFriend(user, other)
                            .then(upsertEdge(user, other, UserRelationship.BLOCKED));
                });
    }

    public Mono<Void> unblock(String user, String other) {
        return repo.findEdge(user, other)
                .filter(r -> UserRelationship.BLOCKED.equals(r.getRelationship()))
                .flatMap(repo::delete);
    }

    /** Casdoor ids of {@code user}'s mutual friends — used by the
     *  session-list filter for the {@code friends} visibility tier. */
    public Mono<Set<String>> friendsOf(String user) {
        return repo.findFriendsOf(user)
                .map(UserRelationship::getOtherCasdoorId)
                .collect(HashSet::new, Set::add);
    }

    /** Casdoor ids that {@code user} has blocked OR has been blocked
     *  by — sessions involving any of these users are filtered out
     *  of {@code user}'s lobby list. */
    public Mono<Set<String>> blocklistFor(String user) {
        Mono<Set<String>> outgoing = repo.findBlocksBy(user)
                .map(UserRelationship::getOtherCasdoorId)
                .collect(HashSet::new, Set::add);
        Mono<Set<String>> incoming = repo.findBlocksAgainst(user)
                .map(UserRelationship::getUserCasdoorId)
                .collect(HashSet::new, Set::add);
        return outgoing.zipWith(incoming).map(t -> {
            Set<String> merged = new HashSet<>(t.getT1());
            merged.addAll(t.getT2());
            return merged;
        });
    }

    public Flux<UserRelationship> incomingRequests(String user) { return repo.findIncomingRequests(user); }
    public Flux<UserRelationship> outgoingRequests(String user) { return repo.findOutgoingRequests(user); }
    public Flux<UserRelationship> friendList(String user)       { return repo.findFriendsOf(user); }
    public Flux<UserRelationship> blockList(String user)        { return repo.findBlocksBy(user); }

    private Mono<UserRelationship> upsertEdge(String user, String other, String relationship) {
        return repo.findEdge(user, other)
                .flatMap(existing -> {
                    existing.setRelationship(relationship);
                    existing.setUpdatedAt(Instant.now());
                    return repo.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    UserRelationship r = new UserRelationship();
                    r.setUserCasdoorId(user);
                    r.setOtherCasdoorId(other);
                    r.setRelationship(relationship);
                    r.setCreatedAt(Instant.now());
                    r.setUpdatedAt(Instant.now());
                    return repo.save(r);
                }));
    }
}

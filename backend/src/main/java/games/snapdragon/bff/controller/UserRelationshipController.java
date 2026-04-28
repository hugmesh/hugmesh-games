package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.UserRelationship;
import games.snapdragon.bff.model.UserRelationshipDto;
import games.snapdragon.bff.service.UserDirectoryService;
import games.snapdragon.bff.service.UserRelationshipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Friend-graph + blocklist endpoints for the authenticated player.
 *
 * <p>The caller's casdoor id is read off the JWT subject — clients
 * cannot pass an arbitrary {@code user} parameter; only the {@code
 * other} target is body-supplied. This rules out one user pretending
 * to be another when sending requests / blocks.
 */
@Tag(name = "Friends", description = "Friend graph + blocklist for the authenticated player.")
@RestController
@RequestMapping("/api/relationships")
public class UserRelationshipController {

    private final UserRelationshipService service;
    private final UserDirectoryService    directory;

    public UserRelationshipController(UserRelationshipService service,
                                      UserDirectoryService directory) {
        this.service   = service;
        this.directory = directory;
        // Side-effect: also cache the caller's own (name, id) on every
        // request so a friend resolving us by name short-circuits the
        // Casdoor round-trip the next time. Wired in via the auth
        // filter would be cleaner, but the controller is the single
        // entry point that already has both claims handy.
    }

    public record TargetBody(String other) {}

    private static String me(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "anonymous";
    }

    /**
     * Resolve the body's `other` field to a Casdoor UUID. Players type
     * usernames into the UI; the friend graph + blocklist tables key
     * on the same casdoor_id format `session_members` uses. Without
     * this resolution the lobby-block filter compares a UUID-keyed
     * `creatorCasdoorId` against a username-keyed blocklist and never
     * matches — see the e2e regression that triggered Liquibase 035.
     *
     * Empty / unknown targets surface as 400 + a friendly message;
     * the friends UI's NotificationService renders that.
     */
    private Mono<String> resolveOther(TargetBody body, Jwt jwt) {
        if (body == null || body.other() == null || body.other().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "target user required"));
        }
        // Cache the caller's own mapping while we have both claims.
        if (jwt != null) {
            String name = jwt.getClaimAsString("name");
            if (name != null) directory.cacheLogin(name, jwt.getSubject());
        }
        return directory.resolveOrPassthrough(body.other())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "user not found: " + body.other())));
    }

    @Operation(summary = "List the caller's mutual friends")
    @GetMapping("/friends")
    public Flux<UserRelationshipDto> friends(@AuthenticationPrincipal Jwt jwt) {
        return service.friendList(me(jwt)).map(this::decorate);
    }

    @Operation(summary = "List incoming friend requests")
    @GetMapping("/friends/incoming")
    public Flux<UserRelationshipDto> incoming(@AuthenticationPrincipal Jwt jwt) {
        return service.incomingRequests(me(jwt)).map(this::decorate);
    }

    @Operation(summary = "List outgoing friend requests")
    @GetMapping("/friends/outgoing")
    public Flux<UserRelationshipDto> outgoing(@AuthenticationPrincipal Jwt jwt) {
        return service.outgoingRequests(me(jwt)).map(this::decorate);
    }

    @Operation(summary = "Send a friend request (auto-accepts if a reciprocal request already exists)")
    @PostMapping("/friends/request")
    public Mono<UserRelationshipDto> request(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return resolveOther(body, jwt)
                .flatMap(otherId -> service.requestFriend(me(jwt), otherId))
                .map(this::decorate);
    }

    @Operation(summary = "Accept an inbound friend request")
    @PostMapping("/friends/accept")
    public Mono<UserRelationshipDto> accept(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return resolveOther(body, jwt)
                .flatMap(otherId -> service.acceptFriend(me(jwt), otherId))
                .map(this::decorate);
    }

    @Operation(summary = "Decline a request or remove an existing friend")
    @PostMapping("/friends/remove")
    public Mono<Map<String,Object>> remove(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return resolveOther(body, jwt)
                .flatMap(otherId -> service.removeFriend(me(jwt), otherId))
                .then(Mono.just(Map.of("ok", true)));
    }

    @Operation(summary = "List users the caller has blocked")
    @GetMapping("/blocks")
    public Flux<UserRelationshipDto> blocks(@AuthenticationPrincipal Jwt jwt) {
        return service.blockList(me(jwt)).map(this::decorate);
    }

    @Operation(summary = "Block a user (publishers and admins are not blockable)")
    @PostMapping("/blocks/add")
    public Mono<UserRelationshipDto> block(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return resolveOther(body, jwt)
                .flatMap(otherId -> service.block(me(jwt), otherId))
                .map(this::decorate);
    }

    @Operation(summary = "Unblock a user")
    @PostMapping("/blocks/remove")
    public Mono<Map<String,Object>> unblock(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return resolveOther(body, jwt)
                .flatMap(otherId -> service.unblock(me(jwt), otherId))
                .then(Mono.just(Map.of("ok", true)));
    }

    /** Wrap a {@link UserRelationship} with display names for the UI. */
    private UserRelationshipDto decorate(UserRelationship r) {
        return UserRelationshipDto.from(
                r,
                directory.displayNameFor(r.getUserCasdoorId()),
                directory.displayNameFor(r.getOtherCasdoorId()));
    }
}

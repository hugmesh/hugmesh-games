package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.UserRelationship;
import games.snapdragon.bff.service.UserRelationshipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
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

    public UserRelationshipController(UserRelationshipService service) {
        this.service = service;
    }

    public record TargetBody(String other) {}

    private static String me(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "anonymous";
    }

    @Operation(summary = "List the caller's mutual friends")
    @GetMapping("/friends")
    public Flux<UserRelationship> friends(@AuthenticationPrincipal Jwt jwt) {
        return service.friendList(me(jwt));
    }

    @Operation(summary = "List incoming friend requests")
    @GetMapping("/friends/incoming")
    public Flux<UserRelationship> incoming(@AuthenticationPrincipal Jwt jwt) {
        return service.incomingRequests(me(jwt));
    }

    @Operation(summary = "List outgoing friend requests")
    @GetMapping("/friends/outgoing")
    public Flux<UserRelationship> outgoing(@AuthenticationPrincipal Jwt jwt) {
        return service.outgoingRequests(me(jwt));
    }

    @Operation(summary = "Send a friend request (auto-accepts if a reciprocal request already exists)")
    @PostMapping("/friends/request")
    public Mono<UserRelationship> request(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return service.requestFriend(me(jwt), body.other());
    }

    @Operation(summary = "Accept an inbound friend request")
    @PostMapping("/friends/accept")
    public Mono<UserRelationship> accept(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return service.acceptFriend(me(jwt), body.other());
    }

    @Operation(summary = "Decline a request or remove an existing friend")
    @PostMapping("/friends/remove")
    public Mono<Map<String,Object>> remove(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return service.removeFriend(me(jwt), body.other()).then(Mono.just(Map.of("ok", true)));
    }

    @Operation(summary = "List users the caller has blocked")
    @GetMapping("/blocks")
    public Flux<UserRelationship> blocks(@AuthenticationPrincipal Jwt jwt) {
        return service.blockList(me(jwt));
    }

    @Operation(summary = "Block a user (publishers and admins are not blockable)")
    @PostMapping("/blocks/add")
    public Mono<UserRelationship> block(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return service.block(me(jwt), body.other());
    }

    @Operation(summary = "Unblock a user")
    @PostMapping("/blocks/remove")
    public Mono<Map<String,Object>> unblock(@AuthenticationPrincipal Jwt jwt, @RequestBody TargetBody body) {
        return service.unblock(me(jwt), body.other()).then(Mono.just(Map.of("ok", true)));
    }
}

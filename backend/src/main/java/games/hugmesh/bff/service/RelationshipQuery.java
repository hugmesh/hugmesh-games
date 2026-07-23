package games.hugmesh.bff.service;

import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Read-only seam onto the friend graph + blocklist for callers
 * (notably {@link GameSessionService}'s lobby filter) that don't
 * need the mutation surface of {@link UserRelationshipService}.
 *
 * <p>Existing as a separate interface (rather than the concrete
 * service) gives the unit test suite a clean mocking target —
 * the inline mock-maker on Java 25 refuses to mock the concrete
 * class but mocks interfaces every time.
 */
public interface RelationshipQuery {

    /** Casdoor ids of mutual friends of {@code user}. */
    Mono<Set<String>> friendsOf(String user);

    /** Casdoor ids that {@code user} has blocked OR has been blocked
     *  by — both directions filter the lobby visibility. */
    Mono<Set<String>> blocklistFor(String user);
}

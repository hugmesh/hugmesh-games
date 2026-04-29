package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.UserRelationship;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRelationshipRepository extends ReactiveCrudRepository<UserRelationship, Long> {

    /** Look up the directed edge from user → other, if any. */
    @Query("""
        SELECT * FROM user_relationships
         WHERE user_casdoor_id  = :user
           AND other_casdoor_id = :other
         LIMIT 1
        """)
    Mono<UserRelationship> findEdge(String user, String other);

    /** Mutual friends of {@code user} — anyone the user has a 'friend'
     *  edge to AND who has a reciprocal 'friend' edge back. */
    @Query("""
        SELECT * FROM user_relationships ur
         WHERE ur.user_casdoor_id = :user
           AND ur.relationship    = 'friend'
           AND EXISTS (
                 SELECT 1 FROM user_relationships ur2
                  WHERE ur2.user_casdoor_id  = ur.other_casdoor_id
                    AND ur2.other_casdoor_id = ur.user_casdoor_id
                    AND ur2.relationship     = 'friend')
         ORDER BY ur.updated_at DESC
        """)
    Flux<UserRelationship> findFriendsOf(String user);

    /** Pending friend requests RECEIVED by {@code user} — others have
     *  asked but the user hasn't accepted yet. */
    @Query("""
        SELECT * FROM user_relationships
         WHERE other_casdoor_id = :user
           AND relationship     = 'friend_request'
         ORDER BY updated_at DESC
        """)
    Flux<UserRelationship> findIncomingRequests(String user);

    /** Pending friend requests SENT by {@code user}. */
    @Query("""
        SELECT * FROM user_relationships
         WHERE user_casdoor_id = :user
           AND relationship    = 'friend_request'
         ORDER BY updated_at DESC
        """)
    Flux<UserRelationship> findOutgoingRequests(String user);

    /** Casdoor ids of users {@code user} has blocked. */
    @Query("""
        SELECT * FROM user_relationships
         WHERE user_casdoor_id = :user
           AND relationship    = 'blocked'
         ORDER BY updated_at DESC
        """)
    Flux<UserRelationship> findBlocksBy(String user);

    /** Casdoor ids of users who have blocked {@code user}. */
    @Query("""
        SELECT * FROM user_relationships
         WHERE other_casdoor_id = :user
           AND relationship     = 'blocked'
        """)
    Flux<UserRelationship> findBlocksAgainst(String user);
}

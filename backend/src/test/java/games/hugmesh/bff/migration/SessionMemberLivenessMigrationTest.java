package games.hugmesh.bff.migration;

import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.SessionMemberRepository;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins migration {@code 061-session-member-liveness.xml} and the reaper's
 * queries against a real Postgres migrated by the full Liquibase changelog
 * (Testcontainers, same lifecycle pattern as
 * {@link ShadowsOfTheNetFederationSeedTest}).
 *
 * <h2>Why this replaced a reflection-over-SQL-text pin</h2>
 *
 * <p>The liveness queries were originally "covered" by asserting the text of
 * their {@code @Query} annotations, on the stated grounds that this module has
 * no database in its test scope. That was simply false — {@code ./mvnw test}
 * already boots a {@code postgres:16} container and runs the whole changelog
 * through Liquibase for the 056 seed test, so 061 is applied to a live
 * Postgres on every suite run and nothing asserted anything about it. String
 * matching on an annotation cannot tell you that {@code COALESCE} does what
 * you think, that a partial index was accepted, or — the one that mattered —
 * that {@code last_seen_at} has no column DEFAULT. That last defect was found
 * and fixed by hand, then re-introducing the single attribute left the entire
 * suite green.
 *
 * <h2>What must hold</h2>
 * <ol>
 *   <li>{@code session_members.last_seen_at} exists, is nullable, and carries
 *       NO column default — Postgres backfills every existing row with a
 *       default at ADD COLUMN time, which would leave 061-2's backfill nothing
 *       to do and hand every already-accumulated ghost a fresh grace window
 *       from the very changeset meant to collect it;</li>
 *   <li>the partial index the sweep rides on exists and is partial;</li>
 *   <li>{@code findStaleActive} really does fall back to {@code joined_at}
 *       through {@code COALESCE} — a NULL {@code last_seen_at} compares as
 *       NULL, never satisfies {@code < cutoff}, and would make exactly the
 *       inherited rows permanently un-reapable;</li>
 *   <li>and the rest of its predicates hold behaviourally: fresh members,
 *       departed members, closed rooms and paused rooms are all excluded.</li>
 * </ol>
 */
@SpringBootTest(properties = {
    // No pre-allocated R2DBC connections so the pool starts empty; every
    // connection is opened AFTER Liquibase commits.
    "spring.r2dbc.pool.initial-size=0",
    // The sweep is @Scheduled and this test writes deliberately-stale rows.
    // Leaving it armed would let a background tick reap the fixtures out from
    // under an assertion. (It also exercises the kill-switch by using it.)
    "app.sessions.reap-enabled=false",
    "spring.cassandra.contact-points=",
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveRepositoriesAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration",
})
@ExtendWith(SessionMemberLivenessMigrationTest.PostgresLifecycle.class)
class SessionMemberLivenessMigrationTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** Same external-DB escape hatch as the other migration tests. */
    private static boolean usingExternalDb() {
        return System.getenv("SG_TEST_PG_R2DBC_URL") != null;
    }

    static class PostgresLifecycle implements
            org.junit.jupiter.api.extension.BeforeAllCallback,
            org.junit.jupiter.api.extension.AfterAllCallback {
        @Override public void beforeAll(org.junit.jupiter.api.extension.ExtensionContext ctx) {
            if (!usingExternalDb()) postgres.start();
        }
        @Override public void afterAll(org.junit.jupiter.api.extension.ExtensionContext ctx) {
            if (!usingExternalDb()) postgres.stop();
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) {
        if (usingExternalDb()) {
            reg.add("spring.r2dbc.url",           () -> System.getenv("SG_TEST_PG_R2DBC_URL"));
            reg.add("spring.r2dbc.username",      () -> System.getenv("SG_TEST_PG_USER"));
            reg.add("spring.r2dbc.password",      () -> System.getenv("SG_TEST_PG_PASS"));
            reg.add("spring.datasource.url",      () -> System.getenv("SG_TEST_PG_JDBC_URL"));
            reg.add("spring.datasource.username", () -> System.getenv("SG_TEST_PG_USER"));
            reg.add("spring.datasource.password", () -> System.getenv("SG_TEST_PG_PASS"));
        } else {
            reg.add("spring.r2dbc.url", () -> String.format(
                    "r2dbc:postgresql://%s:%d/%s",
                    postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
            reg.add("spring.r2dbc.username", postgres::getUsername);
            reg.add("spring.r2dbc.password", postgres::getPassword);
            reg.add("spring.datasource.url", postgres::getJdbcUrl);
            reg.add("spring.datasource.username", postgres::getUsername);
            reg.add("spring.datasource.password", postgres::getPassword);
        }
        reg.add("spring.liquibase.enabled", () -> "true");
    }

    @Autowired private SessionMemberRepository members;
    @Autowired private ConnectionFactory connectionFactory;

    private DatabaseClient db() {
        return DatabaseClient.create(connectionFactory);
    }

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final Instant CUTOFF = NOW.minus(90, ChronoUnit.SECONDS);
    private static final Instant LONG_AGO = NOW.minus(1, ChronoUnit.HOURS);
    private static final Instant JUST_NOW = NOW.minus(5, ChronoUnit.SECONDS);

    /** Unique per test method so the fixtures of one cannot leak into another. */
    private String suffix;

    @BeforeEach
    void freshFixtureNamespace() {
        suffix = "-" + Long.toHexString(System.nanoTime());
    }

    // -------------------------------------------------------------------------
    // Schema shape
    // -------------------------------------------------------------------------

    @Test
    void lastSeenAtIsAddedNullableAndWithoutAColumnDefault() {
        // THE regression this test exists for. Postgres materialises a column
        // default onto every pre-existing row at ADD COLUMN time, so a
        // defaultValueComputed="CURRENT_TIMESTAMP" here leaves zero NULLs for
        // 061-2 to backfill and gives every ghost that accumulated before this
        // migration a fresh grace window — from the changeset introduced to
        // collect them. Re-adding that one attribute previously kept the whole
        // suite green.
        var row = db().sql("""
                    SELECT is_nullable, column_default, data_type
                      FROM information_schema.columns
                     WHERE table_name = 'session_members'
                       AND column_name = 'last_seen_at'
                    """)
                .map(r -> r.get("is_nullable", String.class)
                        + "|" + r.get("column_default", String.class)
                        + "|" + r.get("data_type", String.class))
                .one().block();

        assertThat(row).as("061-1 must add session_members.last_seen_at").isNotNull();
        String[] parts = row.split("\\|", -1);
        assertThat(parts[0]).as("nullable, so 061-2's backfill and the reaper's COALESCE both apply")
                .isEqualTo("YES");
        assertThat(parts[1]).as("NO column default — see the comment above 061-1")
                .isEqualTo("null");
        assertThat(parts[2]).isEqualTo("timestamp with time zone");
    }

    @Test
    void theSweepIndexExistsAndIsPartialOnActiveRows() {
        String def = db().sql("""
                    SELECT indexdef FROM pg_indexes
                     WHERE tablename = 'session_members'
                       AND indexname = 'ix_session_members_active_last_seen'
                    """)
                .map(r -> r.get("indexdef", String.class)).one().block();

        assertThat(def).as("061-3 partial index").isNotNull();
        assertThat(def).contains("last_seen_at");
        assertThat(def).as("partial, or it indexes the whole append-only ledger")
                .contains("WHERE (left_at IS NULL)");
    }

    @Test
    void theBackfillLeavesNoActiveRowWithoutALivenessInstant() {
        // 061-2 re-run verbatim on already-migrated state must still be a
        // no-op-shaped, idempotent statement (the repo's migration protocol),
        // and must never resolve to NOW() — an already-ghosted row handed a
        // fresh window survives its own collection.
        String sessionId = seedSession("open", null, null);
        long stale = seedMember(sessionId, "ghost", false, LONG_AGO, null);

        db().sql("UPDATE session_members SET last_seen_at = joined_at WHERE last_seen_at IS NULL")
                .fetch().rowsUpdated().block();

        SessionMember row = members.findById(stale).block();
        assertThat(row).isNotNull();
        assertThat(row.getLastSeenAt())
                .as("backfilled off joined_at, which is the honest lower bound")
                .isEqualTo(LONG_AGO);
    }

    // -------------------------------------------------------------------------
    // findStaleActive — behaviour, against real SQL
    // -------------------------------------------------------------------------

    @Test
    void aRowWithNoLivenessStampFallsBackToJoinedAtAndIsCollectable() {
        // Without COALESCE a NULL last_seen_at compares as NULL, never
        // satisfies `< cutoff`, and the row is un-reapable FOREVER. Those are
        // precisely the rows this migration inherited, so the sweep would
        // return zero for exactly the ghosts it was built to collect —
        // silently, with nothing downstream to notice.
        String sessionId = seedSession("open", null, null);
        long id = seedMember(sessionId, "pre-061", false, LONG_AGO, null);

        assertThat(staleIds(sessionId)).contains(id);
    }

    @Test
    void aFreshStampSavesARowWhoseJoinedAtIsAncient() {
        // The mirror image: COALESCE must PREFER last_seen_at. A player who
        // joined an hour ago and is polling right now is not a ghost.
        String sessionId = seedSession("open", null, null);
        long id = seedMember(sessionId, "long-player", false, LONG_AGO, JUST_NOW);

        assertThat(staleIds(sessionId)).doesNotContain(id);
    }

    @Test
    void aStampOlderThanTheCutoffIsCollectable() {
        String sessionId = seedSession("open", null, null);
        long id = seedMember(sessionId, "went-quiet", false, JUST_NOW, LONG_AGO);

        assertThat(staleIds(sessionId)).contains(id);
    }

    @Test
    void anAlreadyDepartedRowIsNeverACandidate() {
        String sessionId = seedSession("open", null, null);
        long id = seedMember(sessionId, "left", false, LONG_AGO, LONG_AGO);
        members.stampLeftIfActive(id, NOW).block();

        assertThat(staleIds(sessionId)).doesNotContain(id);
    }

    @Test
    void membersOfAClosedRoomAreNeverCandidates() {
        String sessionId = seedSession("closed", LONG_AGO, null);
        long id = seedMember(sessionId, "stranded", false, LONG_AGO, LONG_AGO);

        assertThat(staleIds(sessionId)).doesNotContain(id);
    }

    @Test
    void membersOfAPausedRoomAreNeverCandidates() {
        // A pause can silence a room's game-side signal wholesale, so every
        // member of one can look stale at once — reaping them would delete the
        // room the instant somebody hit pause.
        String sessionId = seedSession("paused", null, LONG_AGO);
        long a = seedMember(sessionId, "paused-host", true, LONG_AGO, LONG_AGO);
        long b = seedMember(sessionId, "paused-guest", false, LONG_AGO, LONG_AGO);

        assertThat(staleIds(sessionId)).doesNotContain(a, b);
    }

    @Test
    void candidatesComeBackGroupedBySessionOldestRowFirst() {
        String sessionId = seedSession("open", null, null);
        long first  = seedMember(sessionId, "p1", true,  LONG_AGO, LONG_AGO);
        long second = seedMember(sessionId, "p2", false, LONG_AGO, LONG_AGO);

        assertThat(staleIds(sessionId)).containsExactly(first, second);
    }

    // -------------------------------------------------------------------------
    // The writes the reaper and the heartbeat make
    // -------------------------------------------------------------------------

    @Test
    void theRowClaimStampsOnceAndReportsZeroOnTheSecondTry() {
        // The zero row count IS how the reaper learns a concurrent leave or
        // kick got there first — it is what stops the close paths firing twice
        // for a single departure.
        String sessionId = seedSession("open", null, null);
        long id = seedMember(sessionId, "raced", false, LONG_AGO, LONG_AGO);

        assertThat(members.stampLeftIfActive(id, NOW).block()).isEqualTo(1);
        assertThat(members.stampLeftIfActive(id, NOW.plusSeconds(1)).block()).isEqualTo(0);
        assertThat(members.findById(id).block().getLeftAt())
                .as("the losing call must not rewrite the departure instant")
                .isEqualTo(NOW);
    }

    @Test
    void aHeartbeatNeverResurrectsAReleasedSeat() {
        String sessionId = seedSession("open", null, null);
        long id = seedMember(sessionId, "gone", false, LONG_AGO, LONG_AGO);
        members.stampLeftIfActive(id, NOW).block();

        assertThat(members.touchLastSeen(sessionId, "gone", NOW).block()).isEqualTo(0);
        assertThat(members.findById(id).block().getLastSeenAt()).isEqualTo(LONG_AGO);
    }

    @Test
    void aHeartbeatTouchesOnlyTheMemberThatProducedIt() {
        String sessionId = seedSession("open", null, null);
        long mine  = seedMember(sessionId, "me", false, LONG_AGO, LONG_AGO);
        long other = seedMember(sessionId, "you", false, LONG_AGO, LONG_AGO);

        assertThat(members.touchLastSeen(sessionId, "me", NOW).block()).isEqualTo(1);

        assertThat(members.findById(mine).block().getLastSeenAt()).isEqualTo(NOW);
        assertThat(members.findById(other).block().getLastSeenAt())
                .as("one player's poll must not vouch for the whole room")
                .isEqualTo(LONG_AGO);
    }

    @Test
    void theResumeStampCoversTheRoomButNotSeatsReleasedDuringThePause() {
        String sessionId = seedSession("open", null, null);
        long stayed = seedMember(sessionId, "stayed", false, LONG_AGO, LONG_AGO);
        long left   = seedMember(sessionId, "left-mid-pause", false, LONG_AGO, LONG_AGO);
        members.stampLeftIfActive(left, LONG_AGO).block();

        assertThat(members.touchAllActive(sessionId, NOW).block()).isEqualTo(1);

        assertThat(members.findById(stayed).block().getLastSeenAt()).isEqualTo(NOW);
        assertThat(members.findById(left).block().getLastSeenAt()).isEqualTo(LONG_AGO);
    }

    @Test
    void closingASessionReleasesOnlyItsOwnStillActiveSeats() {
        String mine  = seedSession("mine", null, null);
        String other = seedSession("other", null, null);
        long a = seedMember(mine,  "a", true,  LONG_AGO, LONG_AGO);
        long b = seedMember(mine,  "b", false, LONG_AGO, LONG_AGO);
        long c = seedMember(other, "c", false, LONG_AGO, LONG_AGO);

        assertThat(members.stampAllActiveLeft(mine, NOW).block()).isEqualTo(2);

        assertThat(members.findById(a).block().getLeftAt()).isEqualTo(NOW);
        assertThat(members.findById(b).block().getLeftAt()).isEqualTo(NOW);
        assertThat(members.findById(c).block().getLeftAt())
                .as("a close must not reach into another room")
                .isNull();
    }

    @Test
    void closingASessionDoesNotRewriteAnAlreadyStampedDeparture() {
        String sessionId = seedSession("open", null, null);
        long early = seedMember(sessionId, "early", false, LONG_AGO, LONG_AGO);
        members.stampLeftIfActive(early, LONG_AGO).block();

        members.stampAllActiveLeft(sessionId, NOW).block();

        assertThat(members.findById(early).block().getLeftAt())
                .as("rewriting it would corrupt the session-log history")
                .isEqualTo(LONG_AGO);
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private List<Long> staleIds(String sessionId) {
        return members.findStaleActive(CUTOFF)
                .filter(m -> sessionId.equals(m.getSessionId()))
                .map(SessionMember::getId)
                .collectList().block();
    }

    private String seedSession(String tag, Instant closedAt, Instant pausedAt) {
        String id = (tag + suffix + "-" + Long.toHexString(System.nanoTime()));
        db().sql("""
                    INSERT INTO game_sessions
                        (id, name, game_slug, creator_casdoor_id, max_players,
                         created_at, closed_at, paused_at, visibility)
                    VALUES (:id, :name, 'chess', 'host', 4, :createdAt, :closedAt, :pausedAt, 'public')
                    """)
                .bind("id", id)
                .bind("name", tag)
                .bind("createdAt", LONG_AGO)
                .bind("closedAt", closedAt == null ? io.r2dbc.spi.Parameters.in(Instant.class) : io.r2dbc.spi.Parameters.in(closedAt))
                .bind("pausedAt", pausedAt == null ? io.r2dbc.spi.Parameters.in(Instant.class) : io.r2dbc.spi.Parameters.in(pausedAt))
                .fetch().rowsUpdated().block();
        return id;
    }

    /** {@code lastSeenAt == null} models a row written before migration 061. */
    private long seedMember(String sessionId, String casdoorId, boolean creator,
                            Instant joinedAt, Instant lastSeenAt) {
        return db().sql("""
                    INSERT INTO session_members
                        (session_id, casdoor_id, character_id, slot_index, is_creator,
                         joined_at, left_at, last_seen_at)
                    VALUES (:sessionId, :casdoorId, NULL, NULL, :creator, :joinedAt, NULL, :lastSeenAt)
                    RETURNING id
                    """)
                .bind("sessionId", sessionId)
                .bind("casdoorId", casdoorId)
                .bind("creator", creator)
                .bind("joinedAt", joinedAt)
                .bind("lastSeenAt", lastSeenAt == null
                        ? io.r2dbc.spi.Parameters.in(Instant.class)
                        : io.r2dbc.spi.Parameters.in(lastSeenAt))
                .map(r -> r.get("id", Long.class))
                .one().block();
    }
}

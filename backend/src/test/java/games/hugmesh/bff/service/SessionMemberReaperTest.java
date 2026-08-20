package games.hugmesh.bff.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit specs for {@link SessionMemberReaper} — the clock half of the
 * closed-tab ghost fix.
 *
 * <p>All the reap logic (paused-session and tab-takeover exemptions, the
 * concurrent-leave guard, the creator's host-drop route) lives in
 * {@link GameSessionService#reapStaleMembers(Instant)} and is pinned by
 * {@code GameSessionServiceTest}. What is left to prove here is narrow but
 * load-bearing: that the cutoff this class hands down actually reflects the
 * configured timeout, that a nonsense timeout cannot make every live player a
 * candidate, and that a transient database error does not take the scheduler
 * down with it.
 *
 * <p>The collaborator is a recording subclass rather than a Mockito mock —
 * the established idiom in this repo for concrete collaborators (see
 * {@code ModerationServiceTest.FakeGameSessionService}); the super constructor
 * only assigns fields, none of which the stub touches.
 */
class SessionMemberReaperTest {

    /** Captures the cutoff the reaper computed and lets a spec choose the outcome. */
    private static final class RecordingSessions extends GameSessionService {
        final AtomicReference<Instant> lastCutoff = new AtomicReference<>();
        final AtomicInteger sweeps = new AtomicInteger();
        volatile Mono<Long> outcome = Mono.just(0L);

        RecordingSessions() {
            super(null, null, null, null, java.util.Optional.empty(), java.util.Optional.empty(), 15_000L);
        }

        @Override
        public Mono<Long> reapStaleMembers(Instant cutoff) {
            lastCutoff.set(cutoff);
            sweeps.incrementAndGet();
            return outcome;
        }
    }

    @Test
    void sweepAsksForSeatsOlderThanTheConfiguredTimeout() {
        RecordingSessions svc = new RecordingSessions();
        SessionMemberReaper reaper = new SessionMemberReaper(svc, true, 90_000L);

        Instant before = Instant.now();
        reaper.reap();
        Instant after = Instant.now();

        assertThat(svc.sweeps).hasValue(1);
        Instant cutoff = svc.lastCutoff.get();
        assertThat(cutoff).isNotNull();
        // The cutoff is "now minus the timeout", resolved once at method entry.
        assertThat(cutoff).isBetween(before.minus(Duration.ofMillis(90_000)),
                                     after.minus(Duration.ofMillis(90_000)));
    }

    @Test
    void aLongerTimeoutMovesTheCutoffFurtherBack() {
        RecordingSessions shortWindow = new RecordingSessions();
        RecordingSessions longWindow  = new RecordingSessions();
        new SessionMemberReaper(shortWindow, true, 30_000L).reap();
        new SessionMemberReaper(longWindow, true, 600_000L).reap();

        assertThat(longWindow.lastCutoff.get())
                .as("a 10-minute grace period reaps strictly less than a 30-second one")
                .isBefore(shortWindow.lastCutoff.get());
    }

    @Test
    void aZeroTimeoutIsFlooredSoAMemberThatJustHeartbeatIsNotACandidate() {
        // Without the floor a misconfigured 0 would put the cutoff at "now",
        // making every active member — including one that stamped a
        // millisecond ago — instantly stale, and the sweeper would empty every
        // running session on the cluster.
        RecordingSessions svc = new RecordingSessions();
        new SessionMemberReaper(svc, true, 0L).reap();

        assertThat(svc.lastCutoff.get())
                .isBeforeOrEqualTo(Instant.now().minus(Duration.ofMillis(999)));
    }

    @Test
    void aNegativeTimeoutIsFlooredTheSameWay() {
        RecordingSessions svc = new RecordingSessions();
        new SessionMemberReaper(svc, true, -5_000L).reap();

        assertThat(svc.lastCutoff.get())
                .as("a negative window must never resolve to a cutoff in the future")
                .isBefore(Instant.now());
    }

    @Test
    void aTransientDatabaseErrorIsSwallowedSoTheNextSweepStillRuns() {
        // @Scheduled runs on a shared pool: an exception escaping to the
        // scheduler thread would cancel all future executions of this task, so
        // one bad database moment would permanently disable seat reclamation.
        RecordingSessions svc = new RecordingSessions();
        svc.outcome = Mono.error(new IllegalStateException("connection pool exhausted"));
        SessionMemberReaper reaper = new SessionMemberReaper(svc, true, 90_000L);

        reaper.reap();   // must not throw
        reaper.reap();   // and the task is still usable afterwards

        assertThat(svc.sweeps).hasValue(2);
    }

    @Test
    void aSweepThatReleasedSeatsCompletesNormally() {
        RecordingSessions svc = new RecordingSessions();
        svc.outcome = Mono.just(3L);

        new SessionMemberReaper(svc, true, 90_000L).reap();

        assertThat(svc.sweeps).hasValue(1);
    }

    @Test
    void anEmptySweepCompletesNormally() {
        // reapStaleMembers is a count(), which always emits — but a defensive
        // empty must not NPE the subscriber either.
        RecordingSessions svc = new RecordingSessions();
        svc.outcome = Mono.empty();

        new SessionMemberReaper(svc, true, 90_000L).reap();

        assertThat(svc.sweeps).hasValue(1);
    }

    // -------------------------------------------------------------------------
    // Kill-switch
    // -------------------------------------------------------------------------
    //
    // This is a scheduled job that removes people from live rooms. Its safety
    // rests entirely on the assumption that a present player emits a signal,
    // and that assumption is held up by ONE frontend service
    // (SessionHeartbeatService) covering nineteen games that emit nothing of
    // their own. If that leg ever regresses, an operator needs to be able to
    // stop the sweep from config rather than wait for a release — so the
    // switch must actually reach the candidate query, not merely the logging.

    @Test
    void disablingTheReaperStopsTheSweepEntirely() {
        RecordingSessions svc = new RecordingSessions();

        new SessionMemberReaper(svc, false, 90_000L).reap();

        assertThat(svc.sweeps)
                .as("a disabled reaper must not even ASK for candidates")
                .hasValue(0);
        assertThat(svc.lastCutoff.get()).isNull();
    }

    @Test
    void aDisabledReaperStaysDisabledAcrossRepeatedTicks() {
        // @Scheduled keeps firing regardless; the guard is per-invocation.
        RecordingSessions svc = new RecordingSessions();
        SessionMemberReaper reaper = new SessionMemberReaper(svc, false, 90_000L);

        reaper.reap();
        reaper.reap();
        reaper.reap();

        assertThat(svc.sweeps).hasValue(0);
    }

    @Test
    void theShippedConfigExposesTheKillSwitchAndKeepsTheReaperOnByDefault() throws Exception {
        // The ghost seat is a real bug, so the switch is an OFF-switch: an
        // operator opts out, a fresh deployment does not have to opt in. It
        // also has to be DISCOVERABLE — a knob that exists only as a @Value
        // default inside a class nobody greps is not an operational control,
        // so application.yml is the surface this asserts against.
        var cfg = appSessionsConfig();
        assertThat(cfg.get("reap-enabled"))
                .as("app.sessions.reap-enabled must ship, defaulted on")
                .isEqualTo("${APP_SESSIONS_REAP_ENABLED:true}");
    }

    @Test
    void theShippedTimeoutClearsSeveralThrottledBackgroundHeartbeats() throws Exception {
        // The contract with the frontend's SessionHeartbeatService: it beats
        // every 20 s, but a backgrounded tab is still a seated player and
        // browsers clamp its timers to roughly one call per minute. A 90 s
        // window cleared only one and a half of those; three minutes clears
        // three. Shrinking this without shrinking the beat evicts live players.
        long timeoutMs = defaultOf(appSessionsConfig().get("member-timeout-ms"));
        long backgroundTimerClampMs = 60_000L;
        long foregroundBeatMs = 20_000L;

        assertThat(timeoutMs / backgroundTimerClampMs)
                .as("must clear at least three throttled background beats")
                .isGreaterThanOrEqualTo(3);
        assertThat(timeoutMs / foregroundBeatMs)
                .as("and many more foreground ones")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void theHeartbeatWriteLimiterStaysUnderTheClientPollPeriod() throws Exception {
        // A write suppressed for longer than the client's poll period means
        // every other beat is swallowed server-side and the member's effective
        // heartbeat halves in frequency without anything looking wrong.
        long minIntervalMs = defaultOf(appSessionsConfig().get("heartbeat-min-interval-ms"));
        assertThat(minIntervalMs).isLessThan(20_000L);
    }

    /** The `app.sessions` block as it actually ships in application.yml. */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> appSessionsConfig() throws Exception {
        try (var in = SessionMemberReaperTest.class.getClassLoader()
                .getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml on the test classpath").isNotNull();
            // Boot ships snakeyaml; the file has several documents, so scan all.
            for (Object doc : new org.yaml.snakeyaml.Yaml().loadAll(in)) {
                if (!(doc instanceof java.util.Map<?, ?> root)) continue;
                Object app = root.get("app");
                if (!(app instanceof java.util.Map<?, ?> appMap)) continue;
                Object sessions = appMap.get("sessions");
                if (sessions instanceof java.util.Map<?, ?> m) {
                    return (java.util.Map<String, Object>) m;
                }
            }
        }
        throw new AssertionError("application.yml has no app.sessions block — the reaper's "
                + "knobs, including its kill-switch, are undiscoverable to an operator");
    }

    /** Pull the fallback out of a `${ENV_VAR:default}` placeholder. */
    private static long defaultOf(Object placeholder) {
        assertThat(placeholder).as("configured value").isNotNull();
        String s = placeholder.toString();
        int colon = s.lastIndexOf(':');
        assertThat(colon).as("%s carries a default", s).isGreaterThan(0);
        return Long.parseLong(s.substring(colon + 1, s.length() - 1));
    }
}

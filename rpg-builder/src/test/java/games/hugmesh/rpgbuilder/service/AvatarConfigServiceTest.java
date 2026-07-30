package games.hugmesh.rpgbuilder.service;

import games.hugmesh.rpgbuilder.entity.RpgAvatarConfig;
import games.hugmesh.rpgbuilder.entity.RpgProject;
import games.hugmesh.rpgbuilder.repository.*;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the avatar-config GET/PUT surface. The ownership guard
 * runs through a real {@link RpgProjectService} backed by a mocked
 * project repo (AssetStorageServiceTest pattern). The PUT upsert runs
 * against a FAKE {@link DatabaseClient} that emulates Postgres
 * {@code ON CONFLICT (project_id) DO UPDATE} semantics atomically
 * (ConcurrentHashMap.compute keyed on project_id) — so the sequential
 * and concurrent idempotency cases both prove "one row per project, no
 * unique-violation 500", and the SQL itself is pinned to the atomic
 * single-statement shape (a find-then-insert would race).
 */
class AvatarConfigServiceTest {

    private RpgProjectRepository projects;
    private RpgAvatarConfigRepository configs;
    private AvatarConfigService svc;

    /** project_id → stored row: the fake table behind the fake DatabaseClient. */
    private final ConcurrentHashMap<UUID, RpgAvatarConfig> rows = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastSql = new AtomicReference<>("");
    private DatabaseClient db;

    @BeforeEach
    void setUp() {
        projects = mock(RpgProjectRepository.class);
        configs = mock(RpgAvatarConfigRepository.class);
        RpgProjectService projectSvc = new RpgProjectService(
                projects,
                mock(RpgAreaRepository.class),
                mock(RpgRoomRepository.class),
                mock(RpgExitRepository.class),
                mock(RpgItemRepository.class),
                mock(RpgNpcRepository.class),
                mock(RpgScriptRepository.class),
                mock(RpgBuildingRepository.class),
                mock(RpgAssetRepository.class));
        db = fakeUpsertDb();
        svc = new AvatarConfigService(projectSvc, configs, db);
        // GET reflects the fake table so PUT-then-GET round-trips.
        when(configs.findByProjectId(any())).thenAnswer(inv ->
                Mono.justOrEmpty(rows.get(inv.<UUID>getArgument(0))));
    }

    private UUID ownedProject(String owner) {
        UUID pid = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(pid);
        p.setOwnerCasdoorId(owner);
        when(projects.findById(pid)).thenReturn(Mono.just(p));
        return pid;
    }

    // ── GET ─────────────────────────────────────────────────────────────────

    @Test void get_noRowSynthesisesDefaultWithNullId() {
        UUID pid = ownedProject("alice");
        StepVerifier.create(svc.get(pid, "alice"))
                .assertNext(c -> {
                    assertThat(c.getId()).isNull();          // NOT persisted
                    assertThat(c.getProjectId()).isEqualTo(pid);
                    assertThat(c.getBodyShape()).isEqualTo("slim");
                    assertThat(c.getHeadShape()).isEqualTo("round");
                    assertThat(c.getHatType()).isEqualTo("none");
                    assertThat(c.getHeightScale()).isEqualTo(1.0);
                    assertThat(c.getBodyColor()).isEqualTo("#3f59b3");
                    assertThat(c.getHeadColor()).isEqualTo("#e6c8a0");
                    assertThat(c.getHatColor()).isEqualTo("#202020");
                })
                .verifyComplete();
    }

    @Test void get_rejectsForeignProject403() {
        UUID pid = ownedProject("bob");
        StepVerifier.create(svc.get(pid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();
        verify(configs, never()).findByProjectId(any());
    }

    // ── PUT: upsert + defaults ──────────────────────────────────────────────

    @Test void put_persistsAndStampsServerSideIds() {
        UUID pid = ownedProject("alice");
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setId(UUID.randomUUID());                 // ignored — server stamps
        in.setProjectId(UUID.randomUUID());          // ignored — no cross-project write
        in.setBodyShape("stocky");
        in.setHatType("top_hat");
        in.setHeightScale(1.1);
        in.setBodyColor("#e21422");

        StepVerifier.create(svc.put(pid, in, "alice"))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isNotNull();
                    assertThat(saved.getProjectId()).isEqualTo(pid);
                    assertThat(saved.getBodyShape()).isEqualTo("stocky");
                    assertThat(saved.getHatType()).isEqualTo("top_hat");
                    assertThat(saved.getHeightScale()).isEqualTo(1.1);
                    assertThat(saved.getBodyColor()).isEqualTo("#e21422");
                    // omitted fields land on column defaults
                    assertThat(saved.getHeadShape()).isEqualTo("round");
                    assertThat(saved.getHeadColor()).isEqualTo("#e6c8a0");
                })
                .verifyComplete();
        assertThat(rows).hasSize(1);
        // The PUT must be ONE atomic statement, not find-then-insert.
        assertThat(lastSql.get()).contains("ON CONFLICT (project_id) DO UPDATE");
        verify(configs, never()).findByProjectId(any());
    }

    /** R6: an omitted field (null via boxed types) is stamped to its
     *  column default — never 0.0 / "" / a silent clamp to 0.7. */
    @Test void put_emptyBodyYieldsColumnDefaults() {
        UUID pid = ownedProject("alice");
        StepVerifier.create(svc.put(pid, new RpgAvatarConfig(), "alice"))
                .assertNext(saved -> {
                    assertThat(saved.getBodyShape()).isEqualTo("slim");
                    assertThat(saved.getHeadShape()).isEqualTo("round");
                    assertThat(saved.getHatType()).isEqualTo("none");
                    assertThat(saved.getHeightScale()).isEqualTo(1.0);
                    assertThat(saved.getBodyColor()).isEqualTo("#3f59b3");
                    assertThat(saved.getHeadColor()).isEqualTo("#e6c8a0");
                    assertThat(saved.getHatColor()).isEqualTo("#202020");
                })
                .verifyComplete();
    }

    @Test void put_twiceSequentiallyKeepsOneRowAndLatestValues() {
        UUID pid = ownedProject("alice");
        RpgAvatarConfig first = new RpgAvatarConfig();
        first.setBodyShape("round");
        RpgAvatarConfig second = new RpgAvatarConfig();
        second.setBodyShape("stocky");
        second.setHatType("cone");

        UUID idAfterFirst = svc.put(pid, first, "alice").block().getId();
        RpgAvatarConfig after = svc.put(pid, second, "alice").block();

        assertThat(rows).hasSize(1);
        assertThat(after.getId()).isEqualTo(idAfterFirst);   // same row updated
        assertThat(after.getBodyShape()).isEqualTo("stocky");
        assertThat(after.getHatType()).isEqualTo("cone");
    }

    /** R7: two PARALLEL PUTs both succeed and leave exactly one row —
     *  the atomic ON CONFLICT upsert has no observe-then-insert window
     *  for a unique-violation 500. */
    @Test void put_concurrentPutsLeaveOneRowAndNo500() {
        UUID pid = ownedProject("alice");
        RpgAvatarConfig a = new RpgAvatarConfig();
        a.setBodyShape("stocky");
        RpgAvatarConfig b = new RpgAvatarConfig();
        b.setBodyShape("round");

        StepVerifier.create(Mono.zip(
                        svc.put(pid, a, "alice").subscribeOn(Schedulers.parallel()),
                        svc.put(pid, b, "alice").subscribeOn(Schedulers.parallel())))
                .assertNext(t -> {
                    assertThat(t.getT1().getId()).isNotNull();
                    assertThat(t.getT2().getId()).isNotNull();
                })
                .verifyComplete();
        assertThat(rows).hasSize(1);
        assertThat(rows.values().iterator().next().getBodyShape()).isIn("stocky", "round");
    }

    // ── PUT: whitelist / range / colour rejects (SYNCHRONOUS — R4) ─────────

    @Test void put_unknownBodyShapeThrowsSynchronously() {
        UUID pid = ownedProject("alice");
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setBodyShape("gigantic");
        // Thrown at method entry (NOT signalled inside the Mono) so the
        // controller's own @ExceptionHandler maps it to a 400.
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> svc.put(pid, in, "alice"));
        assertThat(err.getMessage()).contains("bodyShape").contains("gigantic")
                .contains("round, slim, stocky");
        verify(db, never()).sql(anyString());
    }

    @Test void put_unknownHeadShapeThrowsSynchronously() {
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setHeadShape("triangular");
        assertThrows(IllegalArgumentException.class,
                () -> svc.put(ownedProject("alice"), in, "alice"));
        verify(db, never()).sql(anyString());
    }

    @Test void put_unknownHatTypeThrowsSynchronously() {
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setHatType("sombrero");
        assertThrows(IllegalArgumentException.class,
                () -> svc.put(ownedProject("alice"), in, "alice"));
        verify(db, never()).sql(anyString());
    }

    @Test void put_presentOutOfRangeHeightIsRejectedNotClamped() {
        RpgAvatarConfig low = new RpgAvatarConfig();
        low.setHeightScale(0.5);
        assertThrows(IllegalArgumentException.class,
                () -> svc.put(ownedProject("alice"), low, "alice"));
        RpgAvatarConfig high = new RpgAvatarConfig();
        high.setHeightScale(2.0);
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> svc.put(ownedProject("alice"), high, "alice"));
        assertThat(err.getMessage()).contains("heightScale");
        verify(db, never()).sql(anyString());
    }

    @Test void put_presentBadHexColourIsRejected() {
        for (String bad : new String[]{"red", "#12345", "#12345g", "e21422", "#e21422aa"}) {
            RpgAvatarConfig in = new RpgAvatarConfig();
            in.setBodyColor(bad);
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> svc.put(ownedProject("alice"), in, "alice"),
                    "expected reject for colour: " + bad);
            assertThat(err.getMessage()).contains("bodyColor");
        }
        verify(db, never()).sql(anyString());
    }

    // ── Ownership + cross-project isolation ─────────────────────────────────

    @Test void put_rejectsForeignProject403AndWritesNothing() {
        UUID pid = ownedProject("bob");
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setBodyShape("stocky");
        StepVerifier.create(svc.put(pid, in, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();
        assertThat(rows).isEmpty();
    }

    @Test void crossProjectIsolation_putOnOneProjectLeavesOtherDefault() {
        UUID p1 = ownedProject("alice");
        UUID p2 = ownedProject("alice");
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setBodyShape("round");
        in.setBodyColor("#e21422");
        svc.put(p1, in, "alice").block();

        StepVerifier.create(svc.get(p2, "alice"))
                .assertNext(c -> {
                    assertThat(c.getId()).isNull();                 // p2 still has no row
                    assertThat(c.getBodyShape()).isEqualTo("slim");
                    assertThat(c.getBodyColor()).isEqualTo("#3f59b3");
                })
                .verifyComplete();
        StepVerifier.create(svc.get(p1, "alice"))
                .assertNext(c -> assertThat(c.getBodyColor()).isEqualTo("#e21422"))
                .verifyComplete();
    }

    // ── fake DatabaseClient: atomic ON CONFLICT emulation ───────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient fakeUpsertDb() {
        DatabaseClient client = mock(DatabaseClient.class);
        when(client.sql(anyString())).thenAnswer(inv -> {
            lastSql.set(inv.getArgument(0));
            Map<String, Object> binds = new ConcurrentHashMap<>();
            DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
            when(spec.bind(anyString(), any())).thenAnswer(b -> {
                binds.put(b.getArgument(0), b.getArgument(1));
                return spec;
            });
            // bindNull must be stubbed too: the nullable texture column uses
            // it, and an unstubbed mock returns null, which silently breaks the
            // whole fluent chain with an NPE two calls later.
            when(spec.bindNull(anyString(), any())).thenReturn(spec);
            when(spec.map(any(BiFunction.class))).thenAnswer(m -> {
                BiFunction<Row, RowMetadata, Object> mapper = m.getArgument(0);
                RowsFetchSpec fetch = mock(RowsFetchSpec.class);
                when(fetch.one()).thenReturn(Mono.defer(() -> {
                    UUID pid = (UUID) binds.get("projectId");
                    Map<String, Object> row = new ConcurrentHashMap<>(binds);
                    RpgAvatarConfig prior = rows.get(pid);
                    row.put("id", prior == null ? UUID.randomUUID() : prior.getId());
                    row.put("project_id", pid);
                    // Map through the service's OWN fromRow so this fake cannot
                    // drift as columns are added — the previous hand-listed
                    // version silently stopped covering new fields.
                    RpgAvatarConfig stored = AvatarConfigService.fromRow(new BindRow(row), null);
                    rows.put(pid, stored);
                    return Mono.just(mapper.apply(new BindRow(row), null));
                }).delaySubscription(Duration.ofMillis(5)));   // widen any race window
                return fetch;
            });
            return spec;
        });
        return client;
    }

    /**
     * Row over the bind map. Bind parameters are named after their columns, so
     * a written value reads back under the same key — which is exactly what
     * {@code RETURNING} does in the real statement.
     */
    private record BindRow(Map<String, Object> values) implements Row {
        @Override public <T> T get(int index, Class<T> type) {
            throw new UnsupportedOperationException("by-index access not expected");
        }
        @SuppressWarnings("unchecked")
        @Override public <T> T get(String name, Class<T> type) {
            return (T) values.get(name);
        }
        @Override public RowMetadata getMetadata() { return null; }
    }
}

package games.hugmesh.bff.aot;

import games.hugmesh.bff.model.AdminStatsDto;
import games.hugmesh.bff.model.AvailableChapterDto;
import games.hugmesh.bff.model.CharacterDto;
import games.hugmesh.bff.model.DashboardDto;
import games.hugmesh.bff.model.GameTimeDto;
import games.hugmesh.bff.model.GameWithChaptersDto;
import games.hugmesh.bff.model.ItemDto;
import games.hugmesh.bff.model.QuestDto;
import games.hugmesh.bff.model.SceneDto;
import games.hugmesh.bff.model.SceneElementDto;
import games.hugmesh.bff.model.SessionDto;
import games.hugmesh.bff.model.SessionHistoryDto;
import games.hugmesh.bff.model.SkillDto;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Registers reflection + resource hints for GraalVM native-image. Spring
 * Boot's AOT plugin discovers most beans on its own; this registrar fills
 * the gaps the plugin can't see — DTO record reflection (Jackson uses
 * canonical-constructor introspection at runtime), and the Liquibase
 * changelog files which are XML resources resolved at runtime.
 *
 * <p>Wired via {@code @ImportRuntimeHints} on {@link games.hugmesh.bff.HugMeshGamesBffApplication}.
 *
 * <p>Phase-2 plan: docs/PLAN-module-federation-and-native.md (section D).
 */
public class HugMeshRuntimeHints implements RuntimeHintsRegistrar {

    private static final Class<?>[] JSON_BOUNDARY_DTOS = {
            AdminStatsDto.class,
            AvailableChapterDto.class,
            CharacterDto.class,
            DashboardDto.class,
            GameTimeDto.class,
            GameWithChaptersDto.class,
            ItemDto.class,
            QuestDto.class,
            SceneDto.class,
            SceneElementDto.class,
            SessionDto.class,
            SessionHistoryDto.class,
            SkillDto.class,
    };

    @Override
    public void registerHints(@NonNull RuntimeHints hints, @Nullable ClassLoader classLoader) {
        // Jackson reads record components via reflection on the canonical
        // constructor + accessor methods. PUBLIC_FIELDS isn't enough for
        // records — DECLARED_* covers private fields backing the record.
        for (Class<?> dto : JSON_BOUNDARY_DTOS) {
            hints.reflection().registerType(dto,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        }

        // No Liquibase resource registration here: under native-image
        // we run migrations from a SEPARATE non-native init container
        // (see docker-compose.native.yml `liquibase` service) and set
        // SPRING_LIQUIBASE_ENABLED=false on the api, so the native
        // binary never loads Liquibase classes. The JVM build still
        // runs Liquibase via Spring auto-config — this hint registry
        // only takes effect under native-image.

        // Per-level Godot bundles ship as classpath resources under
        // `assets/levels/<slug>/`. native-image strips classpath
        // resources by default; AssetController returns 404 without
        // explicit registration. The bundles are big (~100 MB total)
        // but we still need them in the binary so the legacy
        // /api/assets/levels/<slug>/<file> path keeps working alongside
        // the filesystem-pinned hot-upload path.
        hints.resources().registerPattern("assets/levels/.*");
        hints.resources().registerPattern("assets/.*");

        // Netty's jctools-shaded queues read field offsets via Unsafe at
        // class-init. Spring Boot's bundled GraalVM reachability metadata
        // is pinned to netty 4.1.80 but our app uses 4.1.115, so the
        // *Field producer/consumer-index classes drift out of coverage.
        // Register them by name so reflective field lookup succeeds.
        for (String cls : new String[]{
                "io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueueProducerIndexField",
                "io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueueConsumerIndexField",
                "io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueueProducerLimitField",
                "io.netty.util.internal.shaded.org.jctools.queues.unpadded.MpscUnpaddedArrayQueueProducerIndexField",
                "io.netty.util.internal.shaded.org.jctools.queues.unpadded.MpscUnpaddedArrayQueueConsumerIndexField",
                "io.netty.util.internal.shaded.org.jctools.queues.unpadded.MpscUnpaddedArrayQueueProducerLimitField",
        }) {
            hints.reflection().registerTypeIfPresent(classLoader, cls,
                    h -> h.withMembers(MemberCategory.DECLARED_FIELDS,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
        }

        // Casdoor JWKS verification eventually loads the BouncyCastle-style
        // RSA verifier; the OAuth2 resource-server starter handles its own
        // hints, so we deliberately don't duplicate them here.
    }
}

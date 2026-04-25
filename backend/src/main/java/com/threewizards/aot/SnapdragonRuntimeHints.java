package com.threewizards.aot;

import com.threewizards.model.AdminStatsDto;
import com.threewizards.model.AvailableLevelDto;
import com.threewizards.model.CharacterDto;
import com.threewizards.model.DashboardDto;
import com.threewizards.model.GameTimeDto;
import com.threewizards.model.GameWithLevelsDto;
import com.threewizards.model.ItemDto;
import com.threewizards.model.QuestDto;
import com.threewizards.model.SceneDto;
import com.threewizards.model.SceneElementDto;
import com.threewizards.model.SessionDto;
import com.threewizards.model.SessionHistoryDto;
import com.threewizards.model.SkillDto;
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
 * <p>Wired via {@code @ImportRuntimeHints} on {@link com.threewizards.ThreeWizardsApplication}.
 *
 * <p>Phase-2 plan: docs/PLAN-module-federation-and-native.md (section D).
 */
public class SnapdragonRuntimeHints implements RuntimeHintsRegistrar {

    private static final Class<?>[] JSON_BOUNDARY_DTOS = {
            AdminStatsDto.class,
            AvailableLevelDto.class,
            CharacterDto.class,
            DashboardDto.class,
            GameTimeDto.class,
            GameWithLevelsDto.class,
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

        // Liquibase resolves the master changelog + every included file by
        // classpath at runtime; native-image needs to know to bake them in.
        hints.resources().registerPattern("db/changelog/*.xml");

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

        // Liquibase resolves UIService implementations by reflective
        // constructor; ServiceLocator-style. Each implementation must
        // expose its no-arg constructor to native-image.
        for (String cls : new String[]{
                "liquibase.ui.LoggerUIService",
                "liquibase.ui.ConsoleUIService",
                "liquibase.logging.core.JavaLogService",
                "liquibase.logging.core.DefaultLoggerConfiguration",
                "liquibase.executor.jvm.JdbcExecutor",
                "liquibase.command.core.UpdateCommandStep",
                "liquibase.changelog.StandardChangeLogHistoryService",
                "liquibase.lockservice.StandardLockService",
                "liquibase.snapshot.SnapshotGeneratorFactory",
                "liquibase.parser.core.xml.XMLChangeLogSAXParser",
                "liquibase.parser.core.yaml.YamlChangeLogParser",
                "liquibase.parser.core.json.JsonChangeLogParser",
                "liquibase.serializer.core.xml.XMLChangeLogSerializer",
                "liquibase.serializer.core.yaml.YamlChangeLogSerializer",
                "liquibase.serializer.core.json.JsonChangeLogSerializer",
                "liquibase.database.core.PostgresDatabase",
        }) {
            hints.reflection().registerTypeIfPresent(classLoader, cls,
                    h -> h.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.INVOKE_DECLARED_METHODS,
                            MemberCategory.DECLARED_FIELDS));
        }

        // Casdoor JWKS verification eventually loads the BouncyCastle-style
        // RSA verifier; the OAuth2 resource-server starter handles its own
        // hints, so we deliberately don't duplicate them here.
    }
}

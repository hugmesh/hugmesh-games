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

        // Casdoor JWKS verification eventually loads the BouncyCastle-style
        // RSA verifier; the OAuth2 resource-server starter handles its own
        // hints, so we deliberately don't duplicate them here.
    }
}

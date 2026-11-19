package games.hugmesh.bff.aot;

import java.util.function.Predicate;

import org.springframework.data.util.TypeCollector;

/**
 * Keeps database-driver internals out of Spring Data's AOT type collection.
 *
 * <p>{@code UserAvatar.document} is typed as the R2DBC Postgres driver's
 * {@code io.r2dbc.postgresql.codec.Json}. At runtime that is a dialect simple type,
 * but Spring Data's AOT {@link TypeCollector} does not know this. It walks into
 * {@code Json}'s fields, reaches Netty's {@code ByteBuf} and
 * {@code ResourceLeakDetector}, and tries to build a persistent entity for
 * {@code ResourceLeakDetector$DefaultResourceLeak}. That fails on
 * {@code Throwable.backtrace} ("module java.base does not open java.lang"), so
 * {@code process-aot}, and with it the whole native build, aborted.
 *
 * <p>Neither package holds a mapped entity. The drivers create their values
 * themselves, not through Spring Data reflection, so nothing here needs AOT
 * metadata. Registered through {@code META-INF/spring/aot.factories} and combined
 * with Spring Data's own default filters.
 */
public class DriverTypeCollectorFilters implements TypeCollector.TypeCollectorFilters {

    @Override
    public Predicate<Class<?>> classPredicate() {
        return type -> {
            String name = type.getName();
            return !(name.startsWith("io.netty.") || name.startsWith("io.r2dbc."));
        };
    }
}

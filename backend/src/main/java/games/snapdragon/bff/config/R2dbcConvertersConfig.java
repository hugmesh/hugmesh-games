package games.snapdragon.bff.config;

import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;
import io.r2dbc.spi.ConnectionFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * R2DBC type-conversion shims for native-image.
 *
 * <p>Spring Data R2DBC's {@code R2dbcCustomConversions} normally
 * registers JSR-310 fallbacks via reflective discovery. Under
 * GraalVM native-image that discovery skips a few converters, and the
 * Postgres driver hands back {@code OffsetDateTime} / {@code LocalDateTime}
 * for {@code timestamptz} / {@code timestamp} columns while our
 * entities expect {@code Instant}. Result: every {@code findById} on
 * a row with a timestamp column 500s with
 * {@code ConverterNotFoundException}.
 *
 * <p>Registering the two reading converters explicitly makes the
 * conversion deterministic in both modes — JVM and native — without
 * relying on classpath-scanned defaults.
 */
@Configuration(proxyBeanMethods = false)
public class R2dbcConvertersConfig {

    /**
     * Replaces the auto-configured {@link R2dbcCustomConversions} bean
     * (see {@link DataR2dbcAutoConfiguration}) with one that adds the
     * two timestamp converters on top of the dialect's defaults.
     */
    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions(ConnectionFactory connectionFactory) {
        R2dbcDialect dialect = DialectResolver.getDialect(connectionFactory);
        List<Object> converters = new ArrayList<>(dialect.getConverters());
        converters.add(new OffsetDateTimeToInstantConverter());
        converters.add(new LocalDateTimeToInstantConverter());
        return R2dbcCustomConversions.of(dialect, converters);
    }

    @ReadingConverter
    static class OffsetDateTimeToInstantConverter implements Converter<OffsetDateTime, Instant> {
        @Override public Instant convert(OffsetDateTime source) { return source.toInstant(); }
    }

    @ReadingConverter
    static class LocalDateTimeToInstantConverter implements Converter<LocalDateTime, Instant> {
        @Override public Instant convert(LocalDateTime source) {
            // Postgres driver returns LocalDateTime for `timestamp` (no tz).
            // Treat as UTC-stored — same convention the JVM auto-configured
            // converter applies.
            return source.toInstant(ZoneOffset.UTC);
        }
    }
}

package games.hugmesh.bff.aot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import games.hugmesh.bff.model.UserAvatar;
import io.netty.buffer.ByteBuf;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.data.util.TypeCollector;

/**
 * The native build's {@code process-aot} step aborted because Spring Data's AOT type
 * collector walked from {@code UserAvatar.document} ({@code Json}) into Netty
 * internals. These tests pin the filter that keeps driver types out of collection.
 */
class DriverTypeCollectorFiltersTest {

    private final DriverTypeCollectorFilters filters = new DriverTypeCollectorFilters();

    @Test
    void excludesDriverTypes() {
        assertThat(filters.classPredicate().test(Json.class)).isFalse();
        assertThat(filters.classPredicate().test(ByteBuf.class)).isFalse();
    }

    @Test
    void keepsApplicationEntities() {
        assertThat(filters.classPredicate().test(UserAvatar.class)).isTrue();
    }

    @Test
    void isRegisteredInAotFactories() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring/aot.factories")) {
            assertThat(in).as("META-INF/spring/aot.factories on the classpath").isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(text)
                    .contains("org.springframework.data.util.TypeCollector$TypeCollectorFilters")
                    .contains(DriverTypeCollectorFilters.class.getName());
        }
    }

    @Test
    void typeCollectorNoLongerReachesNettyFromUserAvatar() {
        // The real collector, with every registered TypeCollectorFilters provider applied.
        List<Class<?>> reachable = TypeCollector.inspect(UserAvatar.class).list();
        assertThat(reachable).contains(UserAvatar.class);
        assertThat(reachable).noneMatch(c -> c.getName().startsWith("io.netty.")
                || c.getName().startsWith("io.r2dbc."));
    }
}

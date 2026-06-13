package games.snapdragon.bff.model.realtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link org.springframework.data.domain.Persistable} contract for
 * {@code realtime_matches}. The id is application-assigned (the session id),
 * so without {@code Persistable} a populated {@code @Id} makes Spring Data
 * R2DBC {@code save()} emit an UPDATE — 0 rows for a fresh match, a 500 on
 * match start. Mirrors the {@code GameSession} / {@code PlayerProfile}
 * pattern: a row is NOT new unless the start path opts in via
 * {@code markNew()}; the terminal update re-loads and stays not-new.
 */
class RealtimeMatchPersistableTest {

    @Test
    void loadedRowIsNotNewSoSaveUpdates() {
        RealtimeMatch m = new RealtimeMatch();
        m.setSessionId("sess-abc");
        // default: a populated/loaded row is NOT new -> save() = UPDATE (terminal path)
        assertThat(m.isNew()).isFalse();
        assertThat(m.getId()).isEqualTo("sess-abc");
    }

    @Test
    void markNewMakesSaveInsert() {
        RealtimeMatch m = new RealtimeMatch();
        m.setSessionId("sess-abc");
        RealtimeMatch marked = m.markNew();
        // the start path opts in -> save() = INSERT
        assertThat(marked).isSameAs(m);
        assertThat(m.isNew()).isTrue();
        assertThat(m.getId()).isEqualTo("sess-abc");
    }
}

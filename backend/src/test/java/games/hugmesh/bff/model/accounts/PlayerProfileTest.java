package games.hugmesh.bff.model.accounts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link org.springframework.data.domain.Persistable} contract that
 * fixes the {@code GET /api/profile/me/player} 500.
 *
 * <p>{@code PlayerProfile}'s {@code @Id} is application-assigned (the Casdoor
 * subject), so without {@code Persistable} a populated id makes Spring Data
 * R2DBC {@code save()} emit an {@code UPDATE} — 0 rows for a first-time player,
 * which surfaced as a 500. Mirrors the {@code GameSession} pattern: a row is
 * NOT new unless the lazy-create path opts in via {@code markNew()}.
 */
class PlayerProfileTest {

    @Test
    void loadedRowIsNotNewSoSaveUpdates() {
        PlayerProfile p = new PlayerProfile();
        p.setCasdoorUserId("user-abc");
        // default: a populated/loaded row is NOT new -> save() = UPDATE (the PUT path)
        assertThat(p.isNew()).isFalse();
        assertThat(p.getId()).isEqualTo("user-abc");
    }

    @Test
    void markNewMakesSaveInsert() {
        PlayerProfile p = new PlayerProfile();
        p.setCasdoorUserId("user-abc");
        PlayerProfile marked = p.markNew();
        // the lazy-create path opts in -> save() = INSERT (fixes the /me/player 500)
        assertThat(marked).isSameAs(p);
        assertThat(p.isNew()).isTrue();
        assertThat(p.getId()).isEqualTo("user-abc");
    }
}

package games.hugmesh.bff.config;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.GamesAdminPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The games-admin policy bean fails closed: an unset casdoor.organization
 * (application.yml no longer defaults it) stops the BFF at boot instead of
 * being read as the games realm, which re-admitted every bare platform admin
 * on a mesh that forgot CASDOOR_ORGANIZATION.
 */
class SecurityConfigOrganizationTest {

    @SuppressWarnings("unchecked")
    private static SecurityConfig config(String organization) {
        return new SecurityConfig(mock(ObjectProvider.class), new DomainRegistry("", ""),
                mock(ObjectProvider.class), organization, GamesAdminPolicy.GAMES_REALM_ORGANIZATION);
    }

    @Test
    void blankOrganizationStopsTheBff() {
        for (String blank : new String[] {"", "  ", null}) {
            assertThatThrownBy(() -> config(blank).gamesAdminPolicy())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CASDOOR_ORGANIZATION");
        }
    }

    @Test
    void setOrganizationPicksTheRealm() {
        assertThat(config("threewizards").gamesAdminPolicy().realmAdminIsGamesAdmin()).isTrue();
        assertThat(config("hugmesh").gamesAdminPolicy().realmAdminIsGamesAdmin()).isFalse();
    }
}

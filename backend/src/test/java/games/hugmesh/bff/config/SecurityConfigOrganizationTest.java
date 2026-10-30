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
                mock(ObjectProvider.class), organization, GamesAdminPolicy.GAMES_REALM_ORGANIZATION, "");
    }

    @Test
    void blankOrganizationStopsTheBff() {
        for (String blank : new String[] {"", "  ", null}) {
            assertThatThrownBy(() -> config(blank).gamesAdminPolicy())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CASDOOR_ORGANIZATION");
        }
    }

    @SuppressWarnings("unchecked")
    private static SecurityConfig twoIssuers(String gamesRealmIssuers) {
        return new SecurityConfig(mock(ObjectProvider.class),
                new DomainRegistry("", "https://g.example=default,https://m.example=default"),
                mock(ObjectProvider.class), "threewizards", GamesAdminPolicy.GAMES_REALM_ORGANIZATION,
                gamesRealmIssuers);
    }

    @Test
    void twoIssuersWithoutAGamesRealmStopTheBff() {
        // org=threewizards must not turn into "both realms' admins are games admins".
        assertThatThrownBy(() -> twoIssuers("").gamesAdminPolicy())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SECURITY_GAMES_REALM_ISSUERS");
    }

    @Test
    void aGamesRealmIssuerThatIsNotTrustedStopsTheBff() {
        assertThatThrownBy(() -> twoIssuers("https://typo.example").gamesAdminPolicy())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://typo.example");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMistypedGamesRealmIssuerStopsASingleIssuerBffToo() {
        // Otherwise it would start and silently strip every games admin.
        SecurityConfig typo = new SecurityConfig(mock(ObjectProvider.class),
                new DomainRegistry("https://g.example", ""), mock(ObjectProvider.class),
                "threewizards", GamesAdminPolicy.GAMES_REALM_ORGANIZATION, "https://typo.example");
        assertThatThrownBy(typo::gamesAdminPolicy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://typo.example");
    }

    @Test
    void twoIssuersWithAGamesRealmStart() {
        assertThat(twoIssuers("https://g.example/").gamesAdminPolicy().isGamesRealmIssuer("https://g.example"))
                .isTrue();
    }

    @Test
    void setOrganizationPicksTheRealm() {
        assertThat(config("threewizards").gamesAdminPolicy().realmAdminIsGamesAdmin()).isTrue();
        assertThat(config("hugmesh").gamesAdminPolicy().realmAdminIsGamesAdmin()).isFalse();
    }
}

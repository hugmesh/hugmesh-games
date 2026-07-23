package games.hugmesh.bff.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight unit checks on the revocation-list contract. Cassandra
 * wire-level coverage lives in integration tests that provision a
 * Testcontainers cluster — out of scope for the per-PR unit gate.
 *
 * <p>The service's lookup is constant-time hash hit against the
 * cached snapshot. These tests pin that behaviour by writing through
 * a package-private setter on a test-only subclass, then asserting
 * {@code isRevoked} returns the expected boolean. The schema
 * bootstrap + scheduled refresh paths are NOT exercised here (they
 * need a live Cassandra); the Mono-shape of refreshNow is covered
 * by the gateway's own RevocationListServiceTest in the
 * hugmesh-gateway repo so we don't duplicate it across ports.
 */
class RevocationListServiceTest {

    /** Read-only access to the cached snapshot via reflection on a
     *  test subclass. The production class doesn't expose a setter
     *  because revoke state is owned by the refresh tick — but we
     *  need a hook for unit assertions without a Cassandra
     *  dependency. */
    static class TestableRevocationListService extends RevocationListService {
        TestableRevocationListService() {
            // Pass null CQL — the test doesn't trigger any code path
            // that touches it.
            super(null);
        }
        void seed(java.util.Set<String> keys) throws Exception {
            var field = RevocationListService.class.getDeclaredField("revokedKeys");
            field.setAccessible(true);
            field.set(this, java.util.Collections.unmodifiableSet(keys));
        }
    }

    @Test
    void isRevoked_returnsFalse_whenCacheIsEmpty() {
        TestableRevocationListService svc = new TestableRevocationListService();
        assertThat(svc.isRevoked("subject:any-user")).isFalse();
    }

    @Test
    void isRevoked_returnsTrue_whenKeyIsInSnapshot() throws Exception {
        TestableRevocationListService svc = new TestableRevocationListService();
        svc.seed(java.util.Set.of("subject:tw-player-001"));
        assertThat(svc.isRevoked("subject:tw-player-001")).isTrue();
        assertThat(svc.isRevoked("subject:tw-player-002")).isFalse();
    }

    @Test
    void isRevoked_handlesNullKey_withoutThrow() {
        TestableRevocationListService svc = new TestableRevocationListService();
        assertThat(svc.isRevoked(null)).isFalse();
    }

    @Test
    void reasonFor_returnsEmptyStringOnMiss() {
        TestableRevocationListService svc = new TestableRevocationListService();
        assertThat(svc.reasonFor("subject:no-such")).isEqualTo("");
    }

    @Test
    void revocationKeyShapes_acceptedAsOpaqueStrings() throws Exception {
        TestableRevocationListService svc = new TestableRevocationListService();
        svc.seed(java.util.Set.of(
                "subject:tw-admin-001",
                "external:github:42"
        ));
        assertThat(svc.isRevoked("subject:tw-admin-001")).isTrue();
        assertThat(svc.isRevoked("external:github:42")).isTrue();
        assertThat(svc.isRevoked("external:github:99")).isFalse();
    }
}

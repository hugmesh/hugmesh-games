package games.hugmesh.bff.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pinning specs for {@link Crypto}.
 *
 * <p>Crypto owns every UI-visible identifier in the BFF — launch
 * tokens, session ids, pod tags. The contracts this suite locks in:
 *
 * <ul>
 *   <li>{@code mintUid()} produces the right shape (43 chars of
 *       URL-safe base64) for SHA3-256 outputs.</li>
 *   <li>{@code mintUid()} is non-repeating across calls (~256 bits
 *       of entropy means a collision on a fresh JVM is
 *       astronomically unlikely; we assert distinctness on a small
 *       sample as a smoke check).</li>
 *   <li>{@code mintShortUid(n)} returns exactly n chars and clamps
 *       its input range.</li>
 *   <li>{@code sha3_256(bytes)} is deterministic — same input,
 *       same digest.</li>
 *   <li>{@code randomBytes(n)} returns n bytes; {@code n=0} is
 *       allowed (returns an empty array).</li>
 *   <li>{@code mintUidWithBits(bits)} accepts 224/256/384/512 and
 *       rejects everything else with IllegalArgumentException.</li>
 * </ul>
 */
class CryptoTest {

    @Test
    void mintUidProducesUrlSafeBase64OfSha3_256() {
        String uid = Crypto.mintUid();
        // SHA3-256 → 32 bytes → base64url-no-padding → 43 chars.
        assertThat(uid).hasSize(43);
        assertThat(uid).matches("[A-Za-z0-9_\\-]+");
    }

    @Test
    void mintUidIsNonRepeatingAcrossCalls() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertThat(seen.add(Crypto.mintUid())).as("collision on call %d", i).isTrue();
        }
    }

    @Test
    void mintShortUidReturnsRequestedLength() {
        for (int n = 1; n <= 16; n++) {
            assertThat(Crypto.mintShortUid(n)).hasSize(n);
        }
    }

    @Test
    void mintShortUidRejectsOutOfRange() {
        assertThatThrownBy(() -> Crypto.mintShortUid(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Crypto.mintShortUid(44))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha3_256IsDeterministic() {
        byte[] a = Crypto.sha3_256("hugmesh".getBytes());
        byte[] b = Crypto.sha3_256("hugmesh".getBytes());
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(32);
    }

    @Test
    void sha3_256DiffersByInput() {
        byte[] a = Crypto.sha3_256("a".getBytes());
        byte[] b = Crypto.sha3_256("b".getBytes());
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void sha3_256HexReturnsUrlSafeBase64() {
        String hex = Crypto.sha3_256Hex("hello");
        assertThat(hex).hasSize(43);
        assertThat(hex).matches("[A-Za-z0-9_\\-]+");
        // Same input → same output.
        assertThat(Crypto.sha3_256Hex("hello")).isEqualTo(hex);
    }

    @Test
    void randomBytesReturnsRequestedLength() {
        assertThat(Crypto.randomBytes(0)).isEmpty();
        assertThat(Crypto.randomBytes(1)).hasSize(1);
        assertThat(Crypto.randomBytes(64)).hasSize(64);
    }

    @Test
    void mintUidWithBitsAcceptsCanonicalDigestSizes() {
        // SHA3-224 → 28 bytes → 38 base64 chars
        // SHA3-256 → 32 bytes → 43 base64 chars
        // SHA3-384 → 48 bytes → 64 base64 chars
        // SHA3-512 → 64 bytes → 86 base64 chars
        assertThat(Crypto.mintUidWithBits(224)).hasSize(38);
        assertThat(Crypto.mintUidWithBits(256)).hasSize(43);
        assertThat(Crypto.mintUidWithBits(384)).hasSize(64);
        assertThat(Crypto.mintUidWithBits(512)).hasSize(86);
    }

    @Test
    void mintUidWithBitsRejectsUnsupportedSizes() {
        assertThatThrownBy(() -> Crypto.mintUidWithBits(128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("224/256/384/512");
        assertThatThrownBy(() -> Crypto.mintUidWithBits(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

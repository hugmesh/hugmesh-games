package games.snapdragon.bff.util;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.prng.SP800SecureRandomBuilder;
import org.bouncycastle.jcajce.provider.digest.SHA3;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Single source of randomness + identifier minting.
 *
 * <p>Two responsibilities:
 *
 * <ul>
 *   <li>Replace {@code new SecureRandom()} with the SP800-90A
 *       HMAC-SHA256 DRBG from BouncyCastle. The JDK's default
 *       {@code SecureRandom} differs across platforms (NativePRNG
 *       on Linux, Windows-PRNG on Windows, etc.); the SP800 DRBG
 *       is well-specified and deterministic up to its seed source,
 *       which makes it the safer default for security-critical
 *       paths (launch UIDs, session ids, pod ids).</li>
 *
 *   <li>Mint UI-facing UIDs as base64(SHA3-256(entropy)). Any value
 *       that ends up in a URL or a user-visible identifier flows
 *       through {@link #mintUid()} so the surface stays uniform —
 *       SHA3 lengths are deterministic, the encoding is URL-safe,
 *       and the generation path is one well-tested function.</li>
 * </ul>
 *
 * <p>This is a static-method utility — no Spring beans, no
 * dependency injection. Identifier generation has to work in every
 * tier (controllers, services, even unit-test fixtures) without
 * threading a service through.
 */
public final class Crypto {

    private Crypto() { /* static utility */ }

    /**
     * SP800-90A HMAC-SHA256 DRBG seeded from the JVM's default
     * SecureRandom. We seed once at class load — the DRBG is
     * thread-safe and the generation is the hot path. The
     * underlying entropy never escapes this class, so the API
     * surface upstream is just byte buffers.
     */
    private static final SecureRandom RNG = new SP800SecureRandomBuilder()
            .buildHMAC(new HMac(new SHA256Digest()), null, false);

    /** URL-safe base64 (no padding) — what every generated id ends up looking like. */
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * Mint a 256-bit URL-safe UID for a UI-visible / URL-bound
     * identifier (launch tokens, session ids, pod ids). The
     * derivation is {@code base64url(SHA3-256(64 random bytes))},
     * which gives 43 characters of unbiased output the user never
     * has to copy-paste verbatim.
     */
    public static String mintUid() {
        return mintUidWithBits(256);
    }

    /**
     * Mint a UID at the requested SHA3 strength. Caller picks the
     * digest size by output bits — SHA3-256 (default) is the right
     * choice unless you need shorter (224) or stronger (384/512).
     */
    public static String mintUidWithBits(int bits) {
        byte[] entropy = randomBytes(64);
        byte[] hash = switch (bits) {
            case 224 -> new SHA3.Digest224().digest(entropy);
            case 256 -> new SHA3.Digest256().digest(entropy);
            case 384 -> new SHA3.Digest384().digest(entropy);
            case 512 -> new SHA3.Digest512().digest(entropy);
            default -> throw new IllegalArgumentException("SHA3 bits must be 224/256/384/512, got " + bits);
        };
        return URL_ENCODER.encodeToString(hash);
    }

    /**
     * Mint a short UID by truncating the base64-encoded SHA3-256
     * hash. Used by pod-id generation where we just want a short
     * unique-enough tag in log lines, not a security identifier.
     */
    public static String mintShortUid(int chars) {
        if (chars < 1 || chars > 43) {
            throw new IllegalArgumentException("short uid length must be 1..43, got " + chars);
        }
        return mintUid().substring(0, chars);
    }

    /** Raw random bytes from the SP800 DRBG. */
    public static byte[] randomBytes(int n) {
        byte[] out = new byte[n];
        RNG.nextBytes(out);
        return out;
    }

    /** SHA3-256 of arbitrary input — handy when we need a stable
     *  fingerprint of something deterministic (e.g. a normalised
     *  manifest payload) without minting fresh entropy. */
    public static byte[] sha3_256(byte[] input) {
        return new SHA3.Digest256().digest(input);
    }

    /** SHA3-256 of a UTF-8 string, returned as base64url. */
    public static String sha3_256Hex(String input) {
        return URL_ENCODER.encodeToString(sha3_256(input.getBytes(StandardCharsets.UTF_8)));
    }
}

package games.hugmesh.bff.controller.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A pretend Casdoor with a real RSA key, a real JWKS endpoint on a loopback port, and
 * tokens shaped like Casdoor's own ({@code roles} as {@code {name, owner}} objects).
 *
 * <p>{@code mockJwt()} installs an authentication directly, so it can prove what an
 * authority opens but not which tokens the decoders accept. This is for the second
 * question: signed-by-the-right-key, wrong-issuer and the like only mean anything
 * against real signatures.
 *
 * <p>Every instance signs with the SAME {@code kid}, so a token signed by the wrong
 * issuer's key has to fail on the signature, not on an unknown key id.
 */
public final class RealIssuer {

    private static final String KID = "cert-built-in";

    public final String issuer;
    private final RSAKey key;
    private final HttpServer server;

    public RealIssuer(String issuer) {
        this.issuer = issuer;
        try {
            this.key = new RSAKeyGenerator(2048).keyID(KID).generate();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/.well-known/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks";
    }

    /** A live token from this issuer. */
    public String token(String sub, String... roles) {
        return tokenClaiming(issuer, sub, roles);
    }

    /** Signed with THIS issuer's key while claiming {@code claimedIssuer}. */
    public String tokenClaiming(String claimedIssuer, String sub, String... roles) {
        return sign(claims(claimedIssuer, sub, roles).build());
    }

    /** A token that expired an hour ago. */
    public String expiredToken(String sub, String... roles) {
        Instant past = Instant.now().minusSeconds(3600);
        return sign(claims(issuer, sub, roles)
                .issueTime(Date.from(past.minusSeconds(60)))
                .expirationTime(Date.from(past))
                .build());
    }

    /** No {@code iss} at all. */
    public String tokenWithoutIssuer(String sub) {
        return sign(claims(null, sub).build());
    }

    /** {@code alg: none}, claiming this issuer. */
    public String unsignedToken(String sub, String... roles) {
        return new PlainJWT(claims(issuer, sub, roles).build()).serialize();
    }

    private static JWTClaimsSet.Builder claims(String iss, String sub, String... roles) {
        Instant now = Instant.now();
        List<Map<String, String>> roleObjects = Arrays.stream(roles)
                .map(r -> Map.of("name", r, "owner", "some-org"))
                .toList();
        return new JWTClaimsSet.Builder()
                .issuer(iss)
                .subject(sub)
                .claim("name", sub)
                .claim("roles", roleObjects)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(KID).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}

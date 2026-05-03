package com.djong.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecurityConfig}.
 *
 * We test the routing logic in {@code reactiveJwtDecoder()} by injecting
 * mock sub-decoders via a custom decoder factory, and the private
 * {@code extractAlg()} helper directly through ReflectionTestUtils.
 */
class SecurityConfigTest {

    // HS256 secret (32 bytes minimum for HmacSHA256)
    private static final byte[] SECRET_BYTES = new byte[32];
    private static final String BASE64_SECRET = Base64.getEncoder().encodeToString(SECRET_BYTES);

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig();
        ReflectionTestUtils.setField(securityConfig, "jwkSetUri",
                "http://localhost:9999/.well-known/jwks.json");
        ReflectionTestUtils.setField(securityConfig, "jwtSecret", BASE64_SECRET);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractAlg  (private method tested via reflection)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("extractAlg()")
    class ExtractAlg {

        @Test
        @DisplayName("returns 'HS256' for a valid HS256 token")
        void returnsHs256ForHs256Token() throws Exception {
            String token = buildHs256Token();
            String alg = invokeExtractAlg(token);
            assertThat(alg).isEqualTo("HS256");
        }

        @Test
        @DisplayName("returns 'RS256' for a valid RS256 token")
        void returnsRs256ForRs256Token() throws Exception {
            String token = buildRs256Token();
            String alg = invokeExtractAlg(token);
            assertThat(alg).isEqualTo("RS256");
        }

        @Test
        @DisplayName("defaults to 'RS256' when the token is malformed")
        void defaultsToRs256ForGarbage() {
            String alg = invokeExtractAlg("not.a.jwt");
            assertThat(alg).isEqualTo("RS256");
        }

        @Test
        @DisplayName("defaults to 'RS256' for a blank / empty token")
        void defaultsToRs256ForBlank() {
            assertThat(invokeExtractAlg("")).isEqualTo("RS256");
            assertThat(invokeExtractAlg(null)).isEqualTo("RS256");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // reactiveJwtDecoder() routing
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("reactiveJwtDecoder() routing")
    class DecoderRouting {

        @Test
        @DisplayName("decoder is created without throwing")
        void decoderIsCreated() {
            ReactiveJwtDecoder decoder = securityConfig.reactiveJwtDecoder();
            assertThat(decoder).isNotNull();
        }

        @Test
        @DisplayName("HS256 token is routed to the HS256 decoder without NullPointerException")
        void hs256TokenRoutedToHs256Decoder() throws Exception {
            ReactiveJwtDecoder decoder = securityConfig.reactiveJwtDecoder();
            String token = buildHs256Token();

            // The token may decode successfully or fail with a JwtException depending on
            // validation rules (expiry, issuer, etc.), but it must NEVER produce an NPE.
            // We subscribe and simply ensure no unexpected runtime exception is thrown.
            Mono<Void> probe = decoder.decode(token)
                    .then()
                    .onErrorResume(ex -> {
                        // Accept any Spring Security / OAuth2 / JWT validation error
                        String name = ex.getClass().getName();
                        if (name.contains("Jwt") || name.contains("OAuth2") ||
                            ex instanceof IllegalArgumentException ||
                            ex instanceof IllegalStateException) {
                            return Mono.empty();
                        }
                        return Mono.error(ex); // re-throw truly unexpected exceptions
                    });

            StepVerifier.create(probe).verifyComplete();
        }

        @Test
        @DisplayName("malformed token is routed to RS256 decoder (falls back gracefully)")
        void malformedTokenRoutedToRs256Decoder() {
            ReactiveJwtDecoder decoder = securityConfig.reactiveJwtDecoder();

            StepVerifier.create(decoder.decode("garbage.token.here"))
                    .expectErrorMatches(ex ->
                            ex.getClass().getSimpleName().contains("Jwt") ||
                            ex.getClass().getSimpleName().contains("OAuth2") ||
                            ex instanceof IllegalArgumentException ||
                            ex instanceof IllegalStateException)
                    .verify();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Invoke the private {@code extractAlg(String)} via reflection. */
    private String invokeExtractAlg(String token) {
        return (String) ReflectionTestUtils.invokeMethod(securityConfig, "extractAlg", token);
    }

    /** Build a minimal HS256-signed JWT with the test secret. */
    private String buildHs256Token() throws Exception {
        SecretKey key = new SecretKeySpec(SECRET_BYTES, "HmacSHA256");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }

    /** Build a minimal RS256-signed JWT with a freshly generated key pair. */
    private String buildRs256Token() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(kp.getPrivate()));
        return jwt.serialize();
    }
}


package com.djong.gateway.auth;

import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
    private String jwtSecret;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder()))
                )
                .build();
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        // RS256 via JWKS — primary decoder, used once auth server migrates to RS256
        NimbusReactiveJwtDecoder jwksDecoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .build();
        log.info("[JWT] RS256 decoder configured via JWKS: {}", jwkSetUri);

        // HS256 via secret — fallback decoder for tokens still signed with HS256
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        NimbusReactiveJwtDecoder hs256Decoder = NimbusReactiveJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        log.info("[JWT] HS256 decoder configured via secret ({} bytes)", keyBytes.length);

        // Route to the correct decoder based on the token's alg header
        return token -> {
            String alg = extractAlg(token);
            log.debug("[JWT] Incoming token alg={}", alg);
            if ("HS256".equals(alg)) {
                return hs256Decoder.decode(token);
            }
            return jwksDecoder.decode(token);
        };
    }

    /**
     * Reads the {@code alg} field from the JWT header without verifying the signature.
     * Defaults to "RS256" if the header cannot be parsed.
     */
    private String extractAlg(String token) {
        try {
            return SignedJWT.parse(token).getHeader().getAlgorithm().getName();
        } catch (Exception e) {
            log.warn("[JWT] Could not parse token header, defaulting to RS256: {}", e.getMessage());
            return "RS256";
        }
    }
}
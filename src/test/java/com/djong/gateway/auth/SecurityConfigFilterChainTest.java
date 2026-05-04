package com.djong.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SecurityConfig#securityWebFilterChain(ServerHttpSecurity)}.
 *
 * Strategy: boot the full reactive web context (MOCK mode – no real port),
 * intercept the {@link ReactiveJwtDecoder} with a mock so we control whether
 * a bearer token is considered valid, then fire requests via {@link WebTestClient}
 * to assert CSRF-disabled and path-based authorization rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/.well-known/jwks.json",
        "spring.security.oauth2.resourceserver.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class SecurityConfigFilterChainTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityWebFilterChain securityWebFilterChain;

    /**
     * MockitoBean so Spring picks up our stub instead of calling a real JWKS endpoint.
     * Individual tests configure it to either authenticate or reject.
     */
    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    // ─────────────────────────────────────────────────────────────────────────
    // Bean smoke test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("securityWebFilterChain bean is present in the context")
    void filterChainBeanIsPresent() {
        assertThat(securityWebFilterChain).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSRF disabled
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CSRF protection is disabled")
    class CsrfDisabled {

        @Test
        @DisplayName("POST /api/v1/auth/login succeeds without a CSRF token (not 403)")
        void postWithoutCsrfTokenIsNotForbidden() {
            // With CSRF enabled, a state-changing request without a token would yield 403.
            // We expect anything BUT 403 (gateway may return 401 or 404 – that is fine).
            webTestClient.post()
                    .uri("/api/v1/auth/login")
                    .exchange()
                    .expectStatus().value(status ->
                            assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value()));
        }

        @Test
        @DisplayName("POST /api/test (non-permitted) without CSRF token returns 401, not 403")
        void postToSecuredPathWithoutCsrfIsUnauthorizedNotForbidden() {
            when(reactiveJwtDecoder.decode(anyString()))
                    .thenReturn(Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("bad")));

            webTestClient.post()
                    .uri("/api/test")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Authorization rules
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Authorization rules for /api/**")
    class ApiAuthentication {

        @Test
        @DisplayName("GET /api/anything without a token → 401 Unauthorized")
        void unauthenticatedRequestToApiIsRejected() {
            webTestClient.get()
                    .uri("/api/anything")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("GET /api/anything with an invalid bearer token → 401 Unauthorized")
        void invalidBearerTokenIsRejected() {
            when(reactiveJwtDecoder.decode(anyString()))
                    .thenReturn(Mono.error(
                            new org.springframework.security.oauth2.jwt.BadJwtException("invalid token")));

            webTestClient.get()
                    .uri("/api/anything")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.value")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // permitAll paths
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * NOTE: In the current SecurityConfig the rule {@code .pathMatchers("/api/**").authenticated()}
     * is declared FIRST, so it intercepts every path under /api/** – including
     * /api/v1/auth/login and /api/v1/auth/api/auth/login – before the later permitAll
     * rules are evaluated.  The tests below document this actual behaviour.
     */
    @Nested
    @DisplayName("Path authorization rules")
    class PermitAllPaths {

        @Test
        @DisplayName("/api/v1/auth/login is blocked by the leading /api/** rule (→ 401)")
        void authLoginIsBlockedByApiRule() {
            // Because /api/** (authenticated) is declared before the permitAll entries,
            // /api/v1/auth/login is still guarded.
            webTestClient.post()
                    .uri("/api/v1/auth/login")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("/api/v1/auth/api/auth/login is blocked by the leading /api/** rule (→ 401)")
        void authApiAuthLoginIsBlockedByApiRule() {
            webTestClient.post()
                    .uri("/api/v1/auth/api/auth/login")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("non-/api path is accessible without a token (anyExchange permitAll)")
        void nonApiPathIsPermitted() {
            webTestClient.get()
                    .uri("/some/public/resource")
                    .exchange()
                    .expectStatus().value(status ->
                            assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
        }

        @Test
        @DisplayName("root path / is accessible without a token")
        void rootPathIsPermitted() {
            webTestClient.get()
                    .uri("/")
                    .exchange()
                    .expectStatus().value(status ->
                            assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
        }
    }
}


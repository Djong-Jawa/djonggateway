package com.djong.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration-lite tests: verifies the Spring application context loads
 * without binding to a real port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/.well-known/jwks.json",
        "spring.security.oauth2.resourceserver.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class DjongGatewayApplicationTests {

    @Test
    @DisplayName("Spring application context loads successfully")
    void contextLoads() {
        // A failure here means the context could not be assembled.
    }
}

package com.github.wuzhudexiaopacai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "coherer-test-jwt-secret-at-least-32-bytes";

    private JwtService jwtService;
    private SysUser user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 7200, 604800);
        user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("ADMIN");
    }

    @Test
    void issueAccess_parse_containsUserClaims() {
        String token = jwtService.issueAccess(user);
        Claims claims = jwtService.parseAccess(token);

        assertEquals("admin", claims.getSubject());
        assertEquals("ADMIN", claims.get("role"));
        assertEquals("access", claims.get("type"));
        assertNotNull(claims.getExpiration());
    }

    @Test
    void issueRefresh_parseRefresh_succeeds() {
        String token = jwtService.issueRefresh(user);
        assertEquals("refresh", jwtService.parseRefresh(token).get("type"));
    }

    @Test
    void parseAccess_withRefreshToken_throws() {
        String refresh = jwtService.issueRefresh(user);
        assertThrows(IllegalArgumentException.class, () -> jwtService.parseAccess(refresh));
    }

    @Test
    void parseAccess_expiredToken_throws() {
        JwtService expired = new JwtService(SECRET, -60, -60);
        String token = expired.issueAccess(user);
        assertThrows(Exception.class, () -> jwtService.parseAccess(token));
    }

    @Test
    void constructor_shortSecret_throws() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 1, 1));
    }
}

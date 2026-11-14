package com.github.wuzhudexiaopacai.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.auth.dto.LoginRequest;
import com.github.wuzhudexiaopacai.auth.dto.RefreshRequest;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.auth.vo.AuthResponse;
import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.security.JwtService;
import com.github.wuzhudexiaopacai.security.RefreshTokenStore;
import com.github.wuzhudexiaopacai.security.TokenSessionValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    private static final String SECRET = "coherer-test-jwt-secret-at-least-32-bytes";

    private SysUserMapper userMapper;
    private RefreshTokenStore refreshTokenStore;
    private AuthService authService;
    private SysUser user;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        JwtService jwtService = new JwtService(SECRET, 7200, 604800);
        refreshTokenStore = mock(RefreshTokenStore.class);
        when(refreshTokenStore.issue(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("jti-test");
        when(refreshTokenStore.consume(any(), any())).thenReturn(true);
        authService = new AuthService(userMapper, encoder, jwtService, new LoginAttemptService(),
                mock(AuditLogService.class), mock(TokenSessionValidator.class),
                refreshTokenStore, new SimpleMeterRegistry());

        user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setNickname("管理员");
        user.setRole("ADMIN");
        user.setStatus("ACTIVE");
        user.setPasswordHash(encoder.encode("Admin@123"));
        when(userMapper.selectOne(any())).thenReturn(user);
    }

    @Test
    void login_validCredentials_returnsTokens() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("Admin@123");

        AuthResponse response = authService.login(request, "127.0.0.1");

        assertFalse(response.getAccessToken().isBlank());
        assertFalse(response.getRefreshToken().isBlank());
        assertEquals("ADMIN", response.getUser().getRole());
    }

    @Test
    void login_wrongPassword_throws401() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        BizException exception = assertThrows(BizException.class,
                () -> authService.login(request, "127.0.0.1"));
        assertEquals(401, exception.getCode());
    }

    @Test
    void login_repeatedFailures_blocksWith429() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");
        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> authService.login(request, "127.0.0.1"));
        }

        BizException exception = assertThrows(BizException.class,
                () -> authService.login(request, "127.0.0.1"));
        assertEquals(429, exception.getCode());
    }

    @Test
    void login_successResetsFailureCounter() {
        LoginRequest wrong = new LoginRequest();
        wrong.setUsername("admin");
        wrong.setPassword("wrong");
        for (int i = 0; i < 4; i++) {
            assertThrows(BizException.class, () -> authService.login(wrong, "127.0.0.1"));
        }
        LoginRequest right = new LoginRequest();
        right.setUsername("admin");
        right.setPassword("Admin@123");
        authService.login(right, "127.0.0.1");

        assertThrows(BizException.class, () -> authService.login(wrong, "127.0.0.1"));
    }

    @Test
    void login_disabledUser_throws403() {
        user.setStatus("DISABLED");
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("Admin@123");

        BizException exception = assertThrows(BizException.class,
                () -> authService.login(request, "127.0.0.1"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void refresh_validToken_returnsNewTokens() {
        JwtService jwtService = new JwtService(SECRET, 7200, 604800);
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(jwtService.issueRefresh(user, "jti-test"));

        AuthResponse response = authService.refresh(request);

        assertFalse(response.getAccessToken().isBlank());
        assertEquals("admin", response.getUser().getUsername());
    }

    @Test
    void refresh_afterTokenVersionBump_throws401() {
        JwtService jwtService = new JwtService(SECRET, 7200, 604800);
        user.setTokenVersion(0);
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(jwtService.issueRefresh(user, "jti-test"));
        user.setTokenVersion(1);

        BizException exception = assertThrows(BizException.class, () -> authService.refresh(request));
        assertEquals(401, exception.getCode());
    }

    @Test
    void refresh_replayedToken_throws401() {
        JwtService jwtService = new JwtService(SECRET, 7200, 604800);
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(jwtService.issueRefresh(user, "jti-replay"));
        when(refreshTokenStore.consume("jti-replay", "admin")).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> authService.refresh(request));
        assertEquals(401, exception.getCode());
    }

    @Test
    void refresh_invalidToken_throws401() {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken("not-a-token");

        BizException exception = assertThrows(BizException.class, () -> authService.refresh(request));
        assertEquals(401, exception.getCode());
    }
}

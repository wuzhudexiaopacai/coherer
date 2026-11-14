package com.github.wuzhudexiaopacai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DataScopeServiceTest {

    private SysUserMapper userMapper;
    private SpaceService spaceService;
    private DataScopeService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        spaceService = mock(SpaceService.class);
        service = new DataScopeService(userMapper, spaceService);
    }

    @Test
    void adminWithoutSpace_hasUnrestrictedScope() {
        when(userMapper.selectOne(any())).thenReturn(user(null, "ACTIVE"));

        DataScope scope = service.of("admin");

        assertTrue(scope.unrestricted());
        assertTrue(scope.allows(null));
        assertTrue(scope.allows(7L));
        assertNull(service.resolveSpaceIds(scope, null));
    }

    @Test
    void restrictedUser_resolvesDescendantSpaces() {
        when(userMapper.selectOne(any())).thenReturn(user(10L, "ACTIVE"));
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L, 11L, 12L));

        DataScope scope = service.of("viewer");

        assertFalse(scope.unrestricted());
        assertEquals(List.of(10L, 11L, 12L), scope.spaceIds());
        assertTrue(scope.allows(11L));
        assertFalse(scope.allows(99L));
    }

    @Test
    void restrictedUser_withoutSpaceParam_isForcedToOwnSpaces() {
        when(userMapper.selectOne(any())).thenReturn(user(10L, "ACTIVE"));
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L, 11L));

        DataScope scope = service.of("viewer");

        assertEquals(List.of(10L, 11L), service.resolveSpaceIds(scope, null));
    }

    @Test
    void restrictedUser_requestingForeignSpace_isForbidden() {
        when(userMapper.selectOne(any())).thenReturn(user(10L, "ACTIVE"));
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L));

        DataScope scope = service.of("viewer");

        BizException exception = assertThrows(BizException.class,
                () -> service.resolveSpaceIds(scope, 99L));
        assertEquals(403, exception.getCode());
    }

    @Test
    void restrictedUser_requestingOwnSpace_isNarrowed() {
        when(userMapper.selectOne(any())).thenReturn(user(10L, "ACTIVE"));
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L, 11L));
        when(spaceService.descendantIds(11L)).thenReturn(List.of(11L));

        DataScope scope = service.of("viewer");

        assertEquals(List.of(11L), service.resolveSpaceIds(scope, 11L));
    }

    @Test
    void disabledUser_hasEmptyScope() {
        when(userMapper.selectOne(any())).thenReturn(user(10L, "DISABLED"));

        DataScope scope = service.of("viewer");

        assertFalse(scope.unrestricted());
        assertTrue(scope.empty());
        assertFalse(scope.allows(10L));
    }

    @Test
    void missingUser_hasEmptyScope() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertTrue(service.of("ghost").empty());
    }

    @Test
    void requireUnrestricted_rejectsScopedUser() {
        when(userMapper.selectOne(any())).thenReturn(user(10L, "ACTIVE"));
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L));

        BizException exception = assertThrows(BizException.class,
                () -> service.requireUnrestricted("产品管理"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void requireUnrestricted_allowsGlobalUser() {
        when(userMapper.selectOne(any())).thenReturn(user(null, "ACTIVE"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null, List.of()));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.requireUnrestricted("产品管理"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireSpace_rejectsOutOfScope() {
        DataScope scope = DataScope.of(10L, List.of(10L));

        BizException exception = assertThrows(BizException.class,
                () -> service.requireSpace(scope, 20L));
        assertEquals(403, exception.getCode());
    }

    private SysUser user(Long spaceId, String status) {
        SysUser user = new SysUser();
        user.setUsername("tester");
        user.setRole("VIEWER");
        user.setSpaceId(spaceId);
        user.setStatus(status);
        return user;
    }
}

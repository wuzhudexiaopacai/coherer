package com.github.wuzhudexiaopacai.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.auth.dto.PasswordResetRequest;
import com.github.wuzhudexiaopacai.auth.dto.UserCreateRequest;
import com.github.wuzhudexiaopacai.auth.dto.UserUpdateRequest;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.auth.vo.UserVO;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.security.TokenSessionValidator;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class UserServiceTest {

    private SysUserMapper mapper;
    private SpaceService spaceService;
    private TokenSessionValidator sessionValidator;
    private UserService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
        mapper = mock(SysUserMapper.class);
        spaceService = mock(SpaceService.class);
        sessionValidator = mock(TokenSessionValidator.class);
        service = new UserService(new BCryptPasswordEncoder(), spaceService, sessionValidator);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.insert((SysUser) any())).thenReturn(1);
        when(mapper.updateById((SysUser) any())).thenReturn(1);
    }

    @Test
    void create_hashesPasswordAndStoresRole() {
        when(mapper.selectCount(any())).thenReturn(0L);

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("operator1");
        request.setPassword("abc12345");
        request.setNickname("运维一号");
        request.setRole("OPERATOR");

        UserVO vo = service.create(request);

        assertEquals("operator1", vo.getUsername());
        assertEquals("OPERATOR", vo.getRole());
        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((SysUser user) ->
                !"abc12345".equals(user.getPasswordHash())
                        && user.getPasswordHash().startsWith("$2a$")));
    }

    @Test
    void create_duplicateUsername_isConflict() {
        when(mapper.selectCount(any())).thenReturn(1L);
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("admin");
        request.setPassword("abc12345");
        request.setNickname("重复");

        BizException exception = assertThrows(BizException.class, () -> service.create(request));
        assertEquals(409, exception.getCode());
        verify(mapper, never()).insert((SysUser) any());
    }

    @Test
    void create_missingSpace_isNotFound() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(spaceService.exists(99L)).thenReturn(false);
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("viewer1");
        request.setPassword("abc12345");
        request.setNickname("只读");
        request.setSpaceId(99L);

        BizException exception = assertThrows(BizException.class, () -> service.create(request));
        assertEquals(404, exception.getCode());
    }

    @Test
    void update_lastAdminCannotBeDemoted() {
        SysUser admin = user(1L, "admin", "ADMIN", "ACTIVE");
        when(mapper.selectById(1L)).thenReturn(admin);
        when(mapper.selectCount(any())).thenReturn(0L);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole("VIEWER");

        BizException exception = assertThrows(BizException.class,
                () -> service.update(1L, request, "someone-else"));
        assertEquals(409, exception.getCode());
    }

    @Test
    void update_selfDisable_isRejected() {
        SysUser admin = user(1L, "admin", "ADMIN", "ACTIVE");
        when(mapper.selectById(1L)).thenReturn(admin);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setStatus("DISABLED");

        BizException exception = assertThrows(BizException.class,
                () -> service.update(1L, request, "admin"));
        assertEquals(409, exception.getCode());
    }

    @Test
    void update_spaceZero_clearsScope() {
        SysUser viewer = user(2L, "viewer", "VIEWER", "ACTIVE");
        viewer.setSpaceId(10L);
        when(mapper.selectById(2L)).thenReturn(viewer);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setSpaceId(0L);

        UserVO vo = service.update(2L, request, "admin");

        assertEquals(null, vo.getSpaceId());
    }

    @Test
    void resetPassword_encodesNewPassword() {
        when(mapper.selectById(2L)).thenReturn(user(2L, "viewer", "VIEWER", "ACTIVE"));
        PasswordResetRequest request = new PasswordResetRequest();
        request.setPassword("newpass123");

        service.resetPassword(2L, request);

        verify(sessionValidator).invalidate("viewer");
        verify(mapper).updateById(org.mockito.ArgumentMatchers.argThat((SysUser user) ->
                user.getPasswordHash().startsWith("$2a$")
                        && new BCryptPasswordEncoder().matches(
                        "newpass123", user.getPasswordHash())));
    }

    @Test
    void delete_self_isRejected() {
        when(mapper.selectById(1L)).thenReturn(user(1L, "admin", "ADMIN", "ACTIVE"));

        BizException exception = assertThrows(BizException.class,
                () -> service.delete(1L, "admin"));
        assertEquals(409, exception.getCode());
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_lastActiveAdmin_isRejected() {
        when(mapper.selectById(1L)).thenReturn(user(1L, "admin", "ADMIN", "ACTIVE"));
        when(mapper.selectCount(any())).thenReturn(0L);

        BizException exception = assertThrows(BizException.class,
                () -> service.delete(1L, "other-admin"));
        assertEquals(409, exception.getCode());
    }

    @Test
    void delete_nonAdmin_succeeds() {
        when(mapper.selectById(2L)).thenReturn(user(2L, "viewer", "VIEWER", "ACTIVE"));

        service.delete(2L, "admin");

        verify(mapper).deleteById(2L);
    }

    private SysUser user(Long id, String username, String role, String status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}

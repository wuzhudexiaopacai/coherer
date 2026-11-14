package com.github.wuzhudexiaopacai.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenSessionValidatorTest {

    private SysUserMapper userMapper;
    private TokenSessionValidator validator;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        validator = new TokenSessionValidator(userMapper);
    }

    @Test
    void activeUserWithMatchingVersion_isValid() {
        when(userMapper.selectOne(any())).thenReturn(user("ACTIVE", 3));

        assertTrue(validator.isValid("admin", 3));
    }

    @Test
    void versionMismatch_isInvalid() {
        when(userMapper.selectOne(any())).thenReturn(user("ACTIVE", 4));

        assertFalse(validator.isValid("admin", 3));
    }

    @Test
    void disabledUser_isInvalid() {
        when(userMapper.selectOne(any())).thenReturn(user("DISABLED", 3));

        assertFalse(validator.isValid("admin", 3));
    }

    @Test
    void missingUser_isInvalid() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertFalse(validator.isValid("ghost", 0));
    }

    @Test
    void nullVersionOnToken_isTreatedAsZero() {
        when(userMapper.selectOne(any())).thenReturn(user("ACTIVE", 0));

        assertTrue(validator.isValid("admin", null));
    }

    private SysUser user(String status, int tokenVersion) {
        SysUser user = new SysUser();
        user.setUsername("admin");
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}

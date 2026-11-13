package com.github.wuzhudexiaopacai.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.auth.dto.LoginRequest;
import com.github.wuzhudexiaopacai.auth.dto.RefreshRequest;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.auth.vo.AuthResponse;
import com.github.wuzhudexiaopacai.auth.vo.UserInfoVO;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.security.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthResponse login(LoginRequest request) {
        SysUser user = findByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException(403, "账号已禁用");
        }
        return buildResponse(user);
    }

    public AuthResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.parseRefresh(request.getRefreshToken());
        } catch (Exception e) {
            throw new BizException(401, "刷新令牌无效或已过期");
        }
        SysUser user = findByUsername(claims.getSubject());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BizException(401, "用户不可用");
        }
        return buildResponse(user);
    }

    public UserInfoVO me(String username) {
        SysUser user = findByUsername(username);
        if (user == null) {
            throw new BizException(401, "用户不存在");
        }
        return toUserInfo(user);
    }

    private SysUser findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .last("limit 1"));
    }

    private AuthResponse buildResponse(SysUser user) {
        return new AuthResponse(
                jwtService.issueAccess(user),
                jwtService.issueRefresh(user),
                jwtService.accessTtlSeconds(),
                toUserInfo(user));
    }

    private UserInfoVO toUserInfo(SysUser user) {
        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        return vo;
    }
}

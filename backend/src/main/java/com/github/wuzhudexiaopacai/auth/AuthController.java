package com.github.wuzhudexiaopacai.auth;

import com.github.wuzhudexiaopacai.auth.dto.LoginRequest;
import com.github.wuzhudexiaopacai.auth.dto.RefreshRequest;
import com.github.wuzhudexiaopacai.auth.vo.AuthResponse;
import com.github.wuzhudexiaopacai.auth.vo.UserInfoVO;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoVO> me(Authentication authentication) {
        return ApiResponse.ok(authService.me(authentication.getName()));
    }
}

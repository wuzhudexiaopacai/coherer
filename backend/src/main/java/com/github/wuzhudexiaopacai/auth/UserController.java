package com.github.wuzhudexiaopacai.auth;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.auth.dto.PasswordResetRequest;
import com.github.wuzhudexiaopacai.auth.dto.UserCreateRequest;
import com.github.wuzhudexiaopacai.auth.dto.UserUpdateRequest;
import com.github.wuzhudexiaopacai.auth.vo.UserVO;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageParams;
import com.github.wuzhudexiaopacai.common.PageResult;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<PageResult<UserVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(userService.page(
                PageParams.current(current), PageParams.size(size, 100), keyword));
    }

    @PostMapping
    public ApiResponse<UserVO> create(@Valid @RequestBody UserCreateRequest request) {
        UserVO vo = userService.create(request);
        auditLogService.record("USER_CREATE", "user", Map.of(
                "id", String.valueOf(vo.getId()),
                "username", vo.getUsername(),
                "role", vo.getRole()));
        return ApiResponse.ok(vo);
    }

    @PutMapping("/{id}")
    public ApiResponse<UserVO> update(@PathVariable Long id,
                                      @Valid @RequestBody UserUpdateRequest request,
                                      Authentication authentication) {
        UserVO vo = userService.update(id, request, authentication.getName());
        auditLogService.record("USER_UPDATE", "user", Map.of(
                "id", String.valueOf(id),
                "username", vo.getUsername(),
                "role", vo.getRole(),
                "status", vo.getStatus()));
        return ApiResponse.ok(vo);
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody PasswordResetRequest request) {
        userService.resetPassword(id, request);
        auditLogService.record("USER_RESET_PASSWORD", "user", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        userService.delete(id, authentication.getName());
        auditLogService.record("USER_DELETE", "user", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}

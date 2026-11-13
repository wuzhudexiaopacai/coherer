package com.github.wuzhudexiaopacai.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 首次启动初始化管理员账号（口令来自环境变量）。 */
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${coherer.security.admin-password}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, "admin"));
        if (count != null && count > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setTenantId("default");
        admin.setUsername("admin");
        admin.setNickname("平台管理员");
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        userMapper.insert(admin);
        log.warn("已初始化管理员 admin，口令来自 COHERER_ADMIN_PASSWORD，请尽快修改");
    }
}

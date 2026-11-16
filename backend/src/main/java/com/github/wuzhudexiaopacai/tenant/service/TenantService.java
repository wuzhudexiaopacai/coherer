package com.github.wuzhudexiaopacai.tenant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.project.entity.Project;
import com.github.wuzhudexiaopacai.project.mapper.ProjectMapper;
import com.github.wuzhudexiaopacai.security.TenantContext;
import com.github.wuzhudexiaopacai.space.entity.Space;
import com.github.wuzhudexiaopacai.space.mapper.SpaceMapper;
import com.github.wuzhudexiaopacai.tenant.dto.TenantCreateRequest;
import com.github.wuzhudexiaopacai.tenant.dto.TenantUpdateRequest;
import com.github.wuzhudexiaopacai.tenant.entity.Tenant;
import com.github.wuzhudexiaopacai.tenant.mapper.TenantMapper;
import com.github.wuzhudexiaopacai.tenant.vo.TenantVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 租户开通与生命周期：平台管理员专用。 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantLookupService tenantLookupService;
    private final ProjectMapper projectMapper;
    private final SpaceMapper spaceMapper;
    private final SysUserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final PasswordEncoder passwordEncoder;

    public List<TenantVO> list() {
        return tenantMapper.selectList(Wrappers.<Tenant>lambdaQuery()
                        .orderByAsc(Tenant::getCreatedAt)
                        .orderByAsc(Tenant::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantVO create(TenantCreateRequest request) {
        String code = request.getCode().trim();
        if (tenantMapper.selectById(code) != null) {
            throw new BizException(409, "租户代码已存在");
        }
        OffsetDateTime now = OffsetDateTime.now();
        Tenant tenant = new Tenant();
        tenant.setId(code);
        tenant.setName(request.getName().trim());
        tenant.setStatus("ACTIVE");
        tenant.setPlan(StringUtils.hasText(request.getPlan()) ? request.getPlan().trim() : "STANDARD");
        tenant.setDescription(request.getDescription());
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        tenantMapper.insert(tenant);

        TenantContext.runBypass(() -> provision(tenant, request, now));
        tenantLookupService.invalidate(code);
        return toVO(tenantMapper.selectById(code));
    }

    private void provision(Tenant tenant, TenantCreateRequest request, OffsetDateTime now) {
        Project project = new Project();
        project.setTenantId(tenant.getId());
        project.setCode("default");
        project.setName("默认项目");
        project.setStatus("ACTIVE");
        project.setDescription("租户初始化默认项目");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);

        Space space = new Space();
        space.setTenantId(tenant.getId());
        space.setProjectId(project.getId());
        space.setName("默认空间");
        space.setCode("default");
        space.setType("GENERIC");
        space.setSort(0);
        space.setStatus("ACTIVE");
        space.setDescription("租户初始化默认空间");
        spaceMapper.insert(space);

        SysUser admin = new SysUser();
        admin.setTenantId(tenant.getId());
        admin.setUsername(request.getAdminUsername().trim());
        admin.setNickname(StringUtils.hasText(request.getAdminNickname())
                ? request.getAdminNickname().trim() : "租户管理员");
        admin.setPasswordHash(passwordEncoder.encode(request.getAdminPassword()));
        admin.setRole("ADMIN");
        admin.setPlatformAdmin(0);
        admin.setSpaceId(null);
        admin.setStatus("ACTIVE");
        admin.setTokenVersion(0);
        userMapper.insert(admin);

        tenant.setDefaultProjectId(project.getId());
        tenant.setDefaultSpaceId(space.getId());
        tenant.setUpdatedAt(now);
        tenantMapper.updateById(tenant);
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantVO update(String id, TenantUpdateRequest request) {
        Tenant tenant = tenantLookupService.require(id);
        if ("default".equals(id) && "DISABLED".equals(request.getStatus())) {
            throw new BizException(409, "默认租户不能禁用（平台管理员入口）");
        }
        if (StringUtils.hasText(request.getName())) {
            tenant.setName(request.getName().trim());
        }
        if (StringUtils.hasText(request.getStatus())) {
            tenant.setStatus(request.getStatus().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(request.getPlan())) {
            tenant.setPlan(request.getPlan().trim());
        }
        if (request.getDescription() != null) {
            tenant.setDescription(request.getDescription());
        }
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenantMapper.updateById(tenant);
        tenantLookupService.invalidate(id);
        return toVO(tenant);
    }

    private TenantVO toVO(Tenant tenant) {
        TenantVO vo = new TenantVO();
        BeanUtils.copyProperties(tenant, vo);
        TenantContext.callBypass(() -> {
            vo.setUserCount(userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getTenantId, tenant.getId())));
            vo.setProjectCount(projectMapper.selectCount(Wrappers.<Project>lambdaQuery()
                    .eq(Project::getTenantId, tenant.getId())));
            vo.setDeviceCount(deviceMapper.selectCount(Wrappers.<Device>lambdaQuery()
                    .eq(Device::getTenantId, tenant.getId())));
            return null;
        });
        return vo;
    }
}

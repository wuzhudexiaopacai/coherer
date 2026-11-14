package com.github.wuzhudexiaopacai.host.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.Ids;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.device.vo.DeviceVO;
import com.github.wuzhudexiaopacai.host.dto.HostRegisterRequest;
import com.github.wuzhudexiaopacai.host.dto.HostUpdateRequest;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.host.vo.HostRegisterResult;
import com.github.wuzhudexiaopacai.host.vo.HostVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.SecretCipher;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 边缘接入主机：独立认证、空间归属、子设备绑定。
 *
 * <p>Wi-Fi/以太网设备可以不绑定主机（host_id = NULL）直连；
 * 绑定主机的设备命令会经主机转发，主机离线时子设备级联离线。</p>
 */
@Service
@RequiredArgsConstructor
public class HostService extends ServiceImpl<IotHostMapper, IotHost> {

    private final SecretCipher secretCipher;
    private final SpaceService spaceService;
    private final DeviceMapper deviceMapper;
    private final DeviceLookupService deviceLookupService;
    private final HostLookupService hostLookupService;

    @Transactional(rollbackFor = Exception.class)
    public HostRegisterResult register(HostRegisterRequest request, DataScope scope) {
        Long spaceId = request.getSpaceId() == null
                ? (scope.unrestricted() ? SpaceService.DEFAULT_SPACE_ID : scope.rootSpaceId())
                : request.getSpaceId();
        if (spaceId == null) {
            throw new BizException(403, "当前用户未分配数据空间，无法注册主机");
        }
        if (!scope.allows(spaceId)) {
            throw new BizException(403, "无权在该空间注册主机");
        }
        if (!spaceService.exists(spaceId)) {
            throw new BizException(404, "空间不存在");
        }

        String hostId = Ids.hostId();
        String secret = Ids.deviceSecret();
        IotHost host = new IotHost();
        host.setTenantId("default");
        host.setHostId(hostId);
        host.setName(request.getName());
        host.setSpaceId(spaceId);
        host.setModel(request.getModel());
        host.setFirmwareVersion(request.getFirmwareVersion());
        host.setSecretCipher(secretCipher.encrypt(secret));
        host.setStatus("INACTIVE");
        save(host);
        return new HostRegisterResult(hostId, host.getName(), secret);
    }

    public PageResult<HostVO> page(long current, long size, String keyword, List<Long> spaceIds) {
        if (spaceIds != null && spaceIds.isEmpty()) {
            return new PageResult<>(0, current, size, List.of());
        }
        Page<IotHost> page = page(new Page<>(current, size),
                Wrappers.<IotHost>lambdaQuery()
                        .in(spaceIds != null, IotHost::getSpaceId,
                                spaceIds == null ? List.of() : spaceIds)
                        .and(StringUtils.hasText(keyword),
                                w -> w.like(IotHost::getName, keyword)
                                        .or()
                                        .like(IotHost::getHostId, keyword))
                        .orderByDesc(IotHost::getCreatedAt));
        Map<Long, Long> childCounts = childCounts(
                page.getRecords().stream().map(IotHost::getId).toList());
        return PageResult.of(page.convert(host -> toVO(host, childCounts)));
    }

    public HostVO get(Long id, DataScope scope) {
        IotHost host = requireHost(id, scope);
        return toVO(host, childCounts(List.of(id)));
    }

    public List<DeviceVO> devices(Long id, DataScope scope) {
        requireHost(id, scope);
        return deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                        .eq(Device::getHostId, id)
                        .orderByDesc(Device::getCreatedAt))
                .stream()
                .map(this::toDeviceVO)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public HostVO update(Long id, HostUpdateRequest request, DataScope scope) {
        IotHost host = requireHost(id, scope);
        if (request.getSpaceId() != null && !request.getSpaceId().equals(host.getSpaceId())) {
            if (!scope.allows(request.getSpaceId())) {
                throw new BizException(403, "无权移动到该空间");
            }
            if (!spaceService.exists(request.getSpaceId())) {
                throw new BizException(404, "空间不存在");
            }
            List<Long> newSpaceIds = spaceService.descendantIds(request.getSpaceId());
            List<Device> children = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                    .eq(Device::getHostId, id));
            boolean outOfScope = children.stream()
                    .anyMatch(device -> device.getSpaceId() == null
                            || !newSpaceIds.contains(device.getSpaceId()));
            if (outOfScope) {
                throw new BizException(409, "存在子设备不属于目标空间，无法移动主机");
            }
            host.setSpaceId(request.getSpaceId());
        }
        if (StringUtils.hasText(request.getName())) {
            host.setName(request.getName());
        }
        if (request.getModel() != null) {
            host.setModel(request.getModel());
        }
        if (request.getFirmwareVersion() != null) {
            host.setFirmwareVersion(request.getFirmwareVersion());
        }
        updateById(host);
        hostLookupService.invalidate(host);
        return toVO(host, childCounts(List.of(id)));
    }

    @Transactional(rollbackFor = Exception.class)
    public HostVO setEnabled(Long id, DataScope scope, boolean enabled) {
        IotHost host = requireHost(id, scope);
        if (enabled) {
            if ("DISABLED".equals(host.getStatus())) {
                host.setStatus("INACTIVE");
            }
        } else {
            if ("DISABLED".equals(host.getStatus())) {
                return toVO(host, childCounts(List.of(id)));
            }
            host.setStatus("DISABLED");
        }
        updateById(host);
        hostLookupService.invalidate(host);
        return toVO(host, childCounts(List.of(id)));
    }

    @Transactional(rollbackFor = Exception.class)
    public HostRegisterResult rotateSecret(Long id, DataScope scope) {
        IotHost host = requireHost(id, scope);
        String secret = Ids.deviceSecret();
        host.setSecretCipher(secretCipher.encrypt(secret));
        updateById(host);
        hostLookupService.invalidate(host);
        return new HostRegisterResult(host.getHostId(), host.getName(), secret);
    }

    /** 删除主机：先解绑所有子设备，设备本身保留并转为直连。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, DataScope scope) {
        IotHost host = requireHost(id, scope);
        List<Device> children = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .eq(Device::getHostId, id));
        deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getHostId, id)
                .set(Device::getHostId, null));
        children.forEach(device -> deviceLookupService.invalidate(device.getDeviceId()));
        int deleted = baseMapper.deleteById(id);
        if (deleted == 0) {
            throw new BizException(404, "主机不存在");
        }
        hostLookupService.invalidate(host);
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindDevice(Long hostId, String deviceId, DataScope scope) {
        IotHost host = requireHost(hostId, scope);
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        if (!scope.allows(device.getSpaceId())) {
            throw new BizException(403, "无权操作该设备");
        }
        if (!host.getTenantId().equals(device.getTenantId())) {
            throw new BizException(409, "主机与设备不属于同一租户");
        }
        if (device.getSpaceId() == null
                || !spaceService.descendantIds(host.getSpaceId()).contains(device.getSpaceId())) {
            throw new BizException(409, "子设备必须位于主机所在空间或其子空间");
        }
        if (device.getHostId() != null && !device.getHostId().equals(hostId)) {
            throw new BizException(409, "设备已绑定其他主机，请先解绑");
        }
        if (device.getHostId() == null) {
            device.setHostId(hostId);
            deviceMapper.updateById(device);
            deviceLookupService.invalidate(deviceId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void unbindDevice(Long hostId, String deviceId, DataScope scope) {
        requireHost(hostId, scope);
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        if (!scope.allows(device.getSpaceId())) {
            throw new BizException(403, "无权操作该设备");
        }
        if (!hostId.equals(device.getHostId())) {
            throw new BizException(409, "设备未绑定该主机");
        }
        device.setHostId(null);
        deviceMapper.updateById(device);
        deviceLookupService.invalidate(deviceId);
    }

    private IotHost requireHost(Long id, DataScope scope) {
        IotHost host = getById(id);
        if (host == null) {
            throw new BizException(404, "主机不存在");
        }
        if (!scope.allows(host.getSpaceId())) {
            throw new BizException(403, "无权访问该主机");
        }
        return host;
    }

    private Map<Long, Long> childCounts(List<Long> hostIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (hostIds.isEmpty()) {
            return counts;
        }
        List<Map<String, Object>> rows = deviceMapper.selectMaps(
                Wrappers.<Device>query()
                        .select("host_id", "count(*) as cnt")
                        .in("host_id", hostIds)
                        .groupBy("host_id"));
        for (Map<String, Object> row : rows) {
            Object hostId = row.get("host_id");
            Object cnt = row.get("cnt");
            if (hostId instanceof Number id && cnt instanceof Number count) {
                counts.put(id.longValue(), count.longValue());
            }
        }
        return counts;
    }

    private HostVO toVO(IotHost host, Map<Long, Long> childCounts) {
        HostVO vo = new HostVO();
        BeanUtils.copyProperties(host, vo);
        vo.setChildCount(childCounts.getOrDefault(host.getId(), 0L));
        return vo;
    }

    private DeviceVO toDeviceVO(Device device) {
        DeviceVO vo = new DeviceVO();
        BeanUtils.copyProperties(device, vo);
        return vo;
    }
}

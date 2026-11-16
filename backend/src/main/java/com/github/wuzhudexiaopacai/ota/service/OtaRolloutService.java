package com.github.wuzhudexiaopacai.ota.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.ota.dto.OtaRolloutCreateRequest;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.entity.OtaRollout;
import com.github.wuzhudexiaopacai.ota.entity.OtaRolloutDevice;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.event.OtaTaskFinishedEvent;
import com.github.wuzhudexiaopacai.ota.mapper.FirmwareMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaRolloutDeviceMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaRolloutMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import com.github.wuzhudexiaopacai.ota.vo.OtaRolloutDetailVO;
import com.github.wuzhudexiaopacai.project.service.ProjectScopeService;
import com.github.wuzhudexiaopacai.project.service.ProjectService;
import com.github.wuzhudexiaopacai.ota.vo.OtaRolloutDeviceVO;
import com.github.wuzhudexiaopacai.ota.vo.OtaRolloutVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * OTA 灰度批次：
 *
 * <ul>
 *   <li>目标快照：指定设备 / 空间（含子空间）/ 产品全量，创建时冻结成员，避免拓扑变化影响批次</li>
 *   <li>分批下发：同一时间只推进一个 batch，批次内设备并行</li>
 *   <li>暂停/恢复/取消：人工控制推进节奏</li>
 *   <li>失败率熔断：一批完成后失败率超过阈值则暂停后续批次，需要人工恢复</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OtaRolloutService {

    private static final Logger log = LoggerFactory.getLogger(OtaRolloutService.class);
    private static final int MAX_TARGETS = 5000;
    private static final Set<String> ACTIVE_TASK_STATUSES =
            Set.of("PENDING", "SENT", "DOWNLOADING");

    private final OtaRolloutMapper rolloutMapper;
    private final OtaRolloutDeviceMapper rolloutDeviceMapper;
    private final OtaTaskMapper taskMapper;
    private final FirmwareMapper firmwareMapper;
    private final FirmwareService firmwareService;
    private final DeviceLookupService deviceLookupService;
    private final DeviceMapper deviceMapper;
    private final SpaceService spaceService;
    private final ProjectScopeService projectScopeService;
    private final com.github.wuzhudexiaopacai.tenant.service.TenantLookupService tenantLookupService;
    private final OtaService otaService;
    private final MeterRegistry meterRegistry;

    @Transactional(rollbackFor = Exception.class)
    public OtaRolloutDetailVO create(OtaRolloutCreateRequest request, DataScope scope,
                                     String operator) {
        Firmware firmware = firmwareService.require(request.getFirmwareId());
        String tenantId = com.github.wuzhudexiaopacai.security.TenantContext.requireTenant();
        Long projectId = request.getProjectId() == null
                ? tenantLookupService.defaultProjectId(tenantId) : request.getProjectId();
        List<Long> projectSpaceIds = projectScopeService.resolveSpaceIds(scope, projectId, null);
        List<Device> targets = resolveTargets(request, firmware, scope, projectSpaceIds);
        if (targets.isEmpty()) {
            throw new BizException(409, "目标范围内没有可升级设备");
        }
        targets = excludeBusyDevices(targets);
        if (targets.isEmpty()) {
            throw new BizException(409, "目标设备均已有进行中的升级任务");
        }
        if (targets.size() > MAX_TARGETS) {
            throw new BizException(400, "单次灰度最多 " + MAX_TARGETS + " 台设备");
        }

        int batchSize = request.getBatchSize();
        OffsetDateTime now = OffsetDateTime.now();
        OtaRollout rollout = new OtaRollout();
        rollout.setTenantId(firmware.getTenantId() == null ? "default" : firmware.getTenantId());
        rollout.setFirmwareId(firmware.getId());
        rollout.setProductId(firmware.getProductId());
        rollout.setProjectId(projectId);
        rollout.setName(request.getName().trim());
        rollout.setTargetType(request.getTargetType().toUpperCase(Locale.ROOT));
        rollout.setTargetSpaceId(request.getSpaceId());
        rollout.setStatus("PENDING");
        rollout.setBatchSize(batchSize);
        rollout.setFailureThresholdPercent(request.getFailureThresholdPercent());
        rollout.setMaxAttempts(request.getMaxAttempts() == null ? 1 : request.getMaxAttempts());
        rollout.setAutoAdvance(request.getAutoAdvance() == null || request.getAutoAdvance());
        rollout.setCurrentBatch(0);
        rollout.setTotalCount(targets.size());
        rollout.setPendingCount(targets.size());
        rollout.setRunningCount(0);
        rollout.setSuccessCount(0);
        rollout.setFailedCount(0);
        rollout.setCreatedBy(operator);
        rollout.setCreatedAt(now);
        rollout.setUpdatedAt(now);
        rolloutMapper.insert(rollout);

        int index = 0;
        for (Device device : targets) {
            OtaRolloutDevice member = new OtaRolloutDevice();
            member.setTenantId(rollout.getTenantId());
            member.setRolloutId(rollout.getId());
            member.setDeviceId(device.getDeviceId());
            member.setBatchNo(index / batchSize + 1);
            member.setStatus("PENDING");
            member.setUpdatedAt(now);
            rolloutDeviceMapper.insert(member);
            index++;
        }
        meterRegistry.counter("coherer.ota.rollout.created").increment();
        log.info("OTA 灰度批次已创建 rolloutId={} name={} targets={} batchSize={}",
                rollout.getId(), rollout.getName(), targets.size(), batchSize);
        return detail(rollout.getId(), scope, operator);
    }

    public PageResult<OtaRolloutVO> page(long current, long size, Long productId, Long projectId,
                                         String status, DataScope scope, String username) {
        Page<OtaRollout> page = rolloutMapper.selectPage(new Page<>(current, size),
                Wrappers.<OtaRollout>lambdaQuery()
                        .eq(productId != null, OtaRollout::getProductId, productId)
                        .eq(projectId != null, OtaRollout::getProjectId, projectId)
                        .eq(StringUtils.hasText(status), OtaRollout::getStatus, status)
                        .eq(!scope.unrestricted(), OtaRollout::getCreatedBy, username)
                        .orderByDesc(OtaRollout::getCreatedAt));
        Map<Long, Firmware> firmwareMap = firmwareMap(
                page.getRecords().stream().map(OtaRollout::getFirmwareId).toList());
        return PageResult.of(page.convert(rollout -> toVO(rollout,
                firmwareMap.get(rollout.getFirmwareId()), null)));
    }

    public OtaRolloutDetailVO detail(Long id, DataScope scope, String username) {
        OtaRollout rollout = requireVisible(id, scope, username);
        List<OtaRolloutDevice> members = rolloutDeviceMapper.selectList(
                Wrappers.<OtaRolloutDevice>lambdaQuery()
                        .eq(OtaRolloutDevice::getRolloutId, id)
                        .orderByAsc(OtaRolloutDevice::getBatchNo)
                        .orderByAsc(OtaRolloutDevice::getDeviceId));
        Map<Long, OtaTask> tasks = tasksByIds(members.stream()
                .map(OtaRolloutDevice::getTaskId).filter(java.util.Objects::nonNull).toList());
        Map<String, Device> devices = devicesByBizId(members.stream()
                .map(OtaRolloutDevice::getDeviceId).toList());

        List<OtaRolloutDeviceVO> deviceVOs = new ArrayList<>(members.size());
        for (OtaRolloutDevice member : members) {
            OtaRolloutDeviceVO vo = new OtaRolloutDeviceVO();
            vo.setDeviceId(member.getDeviceId());
            Device device = devices.get(member.getDeviceId());
            vo.setDeviceName(device == null ? null : device.getName());
            vo.setBatchNo(member.getBatchNo());
            vo.setStatus(member.getStatus());
            vo.setTaskId(member.getTaskId());
            vo.setError(member.getError());
            OtaTask task = member.getTaskId() == null ? null : tasks.get(member.getTaskId());
            if (task != null) {
                vo.setTaskStatus(task.getStatus());
                vo.setProgress(task.getProgress());
            }
            deviceVOs.add(vo);
        }
        OtaRolloutDetailVO vo = new OtaRolloutDetailVO();
        BeanUtils.copyProperties(toVO(rollout, firmwareMapper.selectById(rollout.getFirmwareId()),
                members), vo);
        vo.setDevices(deviceVOs);
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public OtaRolloutDetailVO pause(Long id, DataScope scope, String username, String reason) {
        OtaRollout rollout = requireVisibleForUpdate(id, scope, username);
        if (!"PENDING".equals(rollout.getStatus()) && !"RUNNING".equals(rollout.getStatus())) {
            throw new BizException(409, "当前状态不能暂停");
        }
        rollout.setStatus("PAUSED");
        rollout.setPauseReason(StringUtils.hasText(reason) ? reason.trim() : "人工暂停");
        rollout.setUpdatedAt(OffsetDateTime.now());
        rolloutMapper.updateById(rollout);
        return detail(id, scope, username);
    }

    @Transactional(rollbackFor = Exception.class)
    public OtaRolloutDetailVO resume(Long id, DataScope scope, String username) {
        OtaRollout rollout = requireVisibleForUpdate(id, scope, username);
        if (!"PAUSED".equals(rollout.getStatus())) {
            throw new BizException(409, "仅暂停中的批次可以恢复");
        }
        rollout.setStatus("RUNNING");
        rollout.setPauseReason(null);
        rollout.setUpdatedAt(OffsetDateTime.now());
        // MyBatis-Plus updateById 默认忽略 null，必须显式 set pause_reason = NULL
        rolloutMapper.update(null, Wrappers.<OtaRollout>lambdaUpdate()
                .eq(OtaRollout::getId, id)
                .set(OtaRollout::getStatus, "RUNNING")
                .set(OtaRollout::getPauseReason, null)
                .set(OtaRollout::getUpdatedAt, rollout.getUpdatedAt()));
        meterRegistry.counter("coherer.ota.rollout.resumed").increment();
        return detail(id, scope, username);
    }

    @Transactional(rollbackFor = Exception.class)
    public OtaRolloutDetailVO cancel(Long id, DataScope scope, String username) {
        OtaRollout rollout = requireVisibleForUpdate(id, scope, username);
        if ("SUCCESS".equals(rollout.getStatus()) || "PARTIAL_FAILED".equals(rollout.getStatus())
                || "FAILED".equals(rollout.getStatus()) || "CANCELED".equals(rollout.getStatus())) {
            throw new BizException(409, "批次已结束");
        }
        rollout.setStatus("CANCELED");
        rollout.setPauseReason("人工取消");
        rollout.setFinishedAt(OffsetDateTime.now());
        rollout.setUpdatedAt(OffsetDateTime.now());
        rolloutMapper.updateById(rollout);
        rolloutDeviceMapper.update(null, Wrappers.<OtaRolloutDevice>lambdaUpdate()
                .eq(OtaRolloutDevice::getRolloutId, id)
                .eq(OtaRolloutDevice::getStatus, "PENDING")
                .set(OtaRolloutDevice::getStatus, "SKIPPED")
                .set(OtaRolloutDevice::getUpdatedAt, OffsetDateTime.now()));
        refreshCounters(rollout);
        meterRegistry.counter("coherer.ota.rollout.canceled").increment();
        return detail(id, scope, username);
    }

    /** 调度器读取待推进批次；真正的推进在独立事务方法 advance 中完成。 */
    public List<Long> activeRolloutIds() {
        return rolloutMapper.selectList(Wrappers.<OtaRollout>lambdaQuery()
                        .select(OtaRollout::getId)
                        .in(OtaRollout::getStatus, List.of("PENDING", "RUNNING"))
                        .orderByAsc(OtaRollout::getId)
                        .last("limit 50"))
                .stream()
                .map(OtaRollout::getId)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void advance(Long rolloutId) {
        OtaRollout rollout = rolloutMapper.selectForUpdate(rolloutId);
        if (rollout == null || (!"PENDING".equals(rollout.getStatus())
                && !"RUNNING".equals(rollout.getStatus()))) {
            return;
        }
        reconcileRunning(rollout);
        if ("PENDING".equals(rollout.getStatus())) {
            startNextBatch(rollout);
            return;
        }
        if (countMembers(rolloutId, "RUNNING") > 0) {
            return;
        }

        int currentBatch = rollout.getCurrentBatch() == null ? 0 : rollout.getCurrentBatch();
        int reviewedBatch = rollout.getReviewedBatch() == null ? 0 : rollout.getReviewedBatch();
        if (currentBatch > 0 && reviewedBatch < currentBatch) {
            BatchStats stats = batchStats(rolloutId, currentBatch);
            rollout.setReviewedBatch(currentBatch);
            rollout.setUpdatedAt(OffsetDateTime.now());
            rolloutMapper.updateById(rollout);
            int rate = stats.finished() == 0 ? 0
                    : (int) Math.round(stats.failed() * 100.0 / stats.finished());
            int threshold = rollout.getFailureThresholdPercent() == null
                    ? 20 : rollout.getFailureThresholdPercent();
            if (stats.failed() > 0 && rate >= threshold) {
                rollout.setStatus("PAUSED");
                rollout.setPauseReason("第 " + currentBatch + " 批失败率 " + rate
                        + "% 达到阈值 " + threshold + "%，已暂停后续批次");
                rolloutMapper.updateById(rollout);
                meterRegistry.counter("coherer.ota.rollout.circuit_open").increment();
                log.warn("OTA 灰度熔断 rolloutId={} batch={} rate={}% threshold={}%",
                        rolloutId, currentBatch, rate, threshold);
                return;
            }
        }

        long pending = countMembers(rolloutId, "PENDING");
        if (pending == 0) {
            finalizeRollout(rollout);
            return;
        }
        if (Boolean.FALSE.equals(rollout.getAutoAdvance()) && currentBatch > 0) {
            rollout.setStatus("PAUSED");
            rollout.setPauseReason("当前批次已完成，等待人工恢复后推进下一批");
            rollout.setUpdatedAt(OffsetDateTime.now());
            rolloutMapper.updateById(rollout);
            return;
        }
        startNextBatch(rollout);
    }

    /** 设备任务到达终态时尽快更新批次成员状态；推进仍由调度器统一做。 */
    @EventListener
    public void onTaskFinished(OtaTaskFinishedEvent event) {
        if (event.rolloutId() == null || !event.terminal()) {
            return;
        }
        String memberStatus = "SUCCESS".equals(event.status()) ? "SUCCESS" : "FAILED";
        rolloutDeviceMapper.update(null, Wrappers.<OtaRolloutDevice>lambdaUpdate()
                .eq(OtaRolloutDevice::getRolloutId, event.rolloutId())
                .eq(OtaRolloutDevice::getDeviceId, event.deviceId())
                .set(OtaRolloutDevice::getStatus, memberStatus)
                .set(event.taskId() != null, OtaRolloutDevice::getTaskId, event.taskId())
                .set(event.error() != null, OtaRolloutDevice::getError, truncate(event.error()))
                .set(OtaRolloutDevice::getUpdatedAt, OffsetDateTime.now()));
        OtaRollout rollout = rolloutMapper.selectById(event.rolloutId());
        if (rollout != null) {
            refreshCounters(rollout);
        }
    }

    private List<Device> resolveTargets(OtaRolloutCreateRequest request, Firmware firmware,
                                        DataScope scope, List<Long> projectSpaceIds) {
        String targetType = request.getTargetType().toUpperCase(Locale.ROOT);
        if ("DEVICE".equals(targetType)) {
            if (request.getDeviceIds() == null || request.getDeviceIds().isEmpty()) {
                throw new BizException(400, "指定设备升级时 deviceIds 不能为空");
            }
            List<Device> devices = new ArrayList<>();
            for (String deviceId : new HashSet<>(request.getDeviceIds())) {
                Device device = deviceLookupService.getByDeviceId(deviceId);
                if (device == null) {
                    throw new BizException(404, "设备不存在: " + deviceId);
                }
                if (!scope.allows(device.getSpaceId())) {
                    throw new BizException(403, "无权操作设备: " + deviceId);
                }
                if (!firmware.getProductId().equals(device.getProductId())) {
                    throw new BizException(409, "设备与固件产品不匹配: " + deviceId);
                }
                if (!projectSpaceIds.contains(device.getSpaceId())) {
                    throw new BizException(409, "设备不属于该项目: " + deviceId);
                }
                if (!"DISABLED".equals(device.getStatus())) {
                    devices.add(device);
                }
            }
            return devices.stream()
                    .sorted(Comparator.comparing(Device::getDeviceId))
                    .toList();
        }
        if ("SPACE".equals(targetType)) {
            if (request.getSpaceId() == null) {
                throw new BizException(400, "按空间升级时 spaceId 不能为空");
            }
            if (!scope.allows(request.getSpaceId()) || !spaceService.exists(request.getSpaceId())) {
                throw new BizException(403, "无权在该空间发起升级");
            }
            if (!projectSpaceIds.contains(request.getSpaceId())) {
                throw new BizException(409, "空间不属于该项目");
            }
            List<Long> spaceIds = spaceService.descendantIds(request.getSpaceId());
            return queryProductDevices(firmware.getProductId(), spaceIds);
        }
        if ("ALL".equals(targetType)) {
            return queryProductDevices(firmware.getProductId(), projectSpaceIds);
        }
        throw new BizException(400, "不支持的目标类型: " + targetType);
    }

    private List<Device> queryProductDevices(Long productId, List<Long> spaceIds) {
        if (spaceIds != null && spaceIds.isEmpty()) {
            return List.of();
        }
        return deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .eq(Device::getProductId, productId)
                .ne(Device::getStatus, "DISABLED")
                .in(spaceIds != null, Device::getSpaceId,
                        spaceIds == null ? List.of() : spaceIds)
                .orderByAsc(Device::getDeviceId));
    }

    private List<Device> excludeBusyDevices(List<Device> devices) {
        List<String> ids = devices.stream().map(Device::getDeviceId).toList();
        Set<String> busy = taskMapper.selectList(Wrappers.<OtaTask>lambdaQuery()
                        .select(OtaTask::getDeviceId)
                        .in(OtaTask::getDeviceId, ids)
                        .in(OtaTask::getStatus, ACTIVE_TASK_STATUSES))
                .stream()
                .map(OtaTask::getDeviceId)
                .collect(Collectors.toSet());
        if (busy.isEmpty()) {
            return devices;
        }
        return devices.stream()
                .filter(device -> !busy.contains(device.getDeviceId()))
                .toList();
    }

    private void startNextBatch(OtaRollout rollout) {
        // 取最小的待下发批次，而不是 currentBatch + 1：
        // 若进程在“写入 current_batch 后、设备全部下发前”崩溃，下一轮仍会补发该批剩余成员。
        OtaRolloutDevice firstPending = rolloutDeviceMapper.selectOne(
                Wrappers.<OtaRolloutDevice>lambdaQuery()
                        .eq(OtaRolloutDevice::getRolloutId, rollout.getId())
                        .eq(OtaRolloutDevice::getStatus, "PENDING")
                        .orderByAsc(OtaRolloutDevice::getBatchNo)
                        .last("limit 1"));
        if (firstPending == null) {
            finalizeRollout(rollout);
            return;
        }
        int nextBatch = firstPending.getBatchNo();
        List<OtaRolloutDevice> members = rolloutDeviceMapper.selectList(
                Wrappers.<OtaRolloutDevice>lambdaQuery()
                        .eq(OtaRolloutDevice::getRolloutId, rollout.getId())
                        .eq(OtaRolloutDevice::getBatchNo, nextBatch)
                        .eq(OtaRolloutDevice::getStatus, "PENDING")
                        .orderByAsc(OtaRolloutDevice::getDeviceId));
        Firmware firmware = firmwareService.require(rollout.getFirmwareId());
        OffsetDateTime now = OffsetDateTime.now();
        rollout.setStatus("RUNNING");
        rollout.setCurrentBatch(nextBatch);
        rollout.setUpdatedAt(now);
        if (rollout.getStartedAt() == null) {
            rollout.setStartedAt(now);
        }
        rolloutMapper.updateById(rollout);

        for (OtaRolloutDevice member : members) {
            Device device = deviceLookupService.getByDeviceId(member.getDeviceId());
            if (device == null || "DISABLED".equals(device.getStatus())) {
                updateMember(member, "FAILED", null, "设备不存在或已禁用");
                continue;
            }
            try {
                OtaService.OtaTaskDispatch dispatch = otaService.startForDevice(
                        firmware, device, rollout.getCreatedBy(), rollout.getId(), nextBatch,
                        rollout.getMaxAttempts() == null ? 1 : rollout.getMaxAttempts());
                if ("FAILED".equals(dispatch.status())) {
                    updateMember(member, "FAILED", dispatch.taskId(), dispatch.error());
                } else {
                    markMemberRunning(member, dispatch.taskId());
                }
            } catch (BizException e) {
                updateMember(member, "FAILED", null, e.getMessage());
            } catch (Exception e) {
                log.warn("OTA 灰度下发异常 rolloutId={} deviceId={} err={}",
                        rollout.getId(), member.getDeviceId(), e.getMessage());
                updateMember(member, "FAILED", null, "下发异常: " + e.getMessage());
            }
        }
        refreshCounters(rollout);
        log.info("OTA 灰度批次推进 rolloutId={} batch={} members={}",
                rollout.getId(), nextBatch, members.size());
    }

    /**
     * 只在成员仍为 PENDING 时切 RUNNING。设备可能极快完成并先一步把成员写成
     * SUCCESS/FAILED，此时不能用 RUNNING 覆盖终态。
     */
    private void markMemberRunning(OtaRolloutDevice member, Long taskId) {
        rolloutDeviceMapper.update(null, Wrappers.<OtaRolloutDevice>lambdaUpdate()
                .eq(OtaRolloutDevice::getRolloutId, member.getRolloutId())
                .eq(OtaRolloutDevice::getDeviceId, member.getDeviceId())
                .eq(OtaRolloutDevice::getStatus, "PENDING")
                .set(OtaRolloutDevice::getStatus, "RUNNING")
                .set(OtaRolloutDevice::getTaskId, taskId)
                .set(OtaRolloutDevice::getUpdatedAt, OffsetDateTime.now()));
    }

    private void updateMember(OtaRolloutDevice member, String status, Long taskId, String error) {
        member.setStatus(status);
        member.setTaskId(taskId);
        member.setError(truncate(error));
        member.setUpdatedAt(OffsetDateTime.now());
        rolloutDeviceMapper.update(null, Wrappers.<OtaRolloutDevice>lambdaUpdate()
                .eq(OtaRolloutDevice::getRolloutId, member.getRolloutId())
                .eq(OtaRolloutDevice::getDeviceId, member.getDeviceId())
                .set(OtaRolloutDevice::getStatus, status)
                .set(OtaRolloutDevice::getTaskId, taskId)
                .set(OtaRolloutDevice::getError, truncate(error))
                .set(OtaRolloutDevice::getUpdatedAt, OffsetDateTime.now()));
    }

    private void reconcileRunning(OtaRollout rollout) {
        List<OtaRolloutDevice> running = rolloutDeviceMapper.selectList(
                Wrappers.<OtaRolloutDevice>lambdaQuery()
                        .eq(OtaRolloutDevice::getRolloutId, rollout.getId())
                        .eq(OtaRolloutDevice::getStatus, "RUNNING")
                        .isNotNull(OtaRolloutDevice::getTaskId));
        if (running.isEmpty()) {
            return;
        }
        Map<Long, OtaTask> tasks = tasksByIds(running.stream()
                .map(OtaRolloutDevice::getTaskId).toList());
        boolean changed = false;
        for (OtaRolloutDevice member : running) {
            OtaTask task = tasks.get(member.getTaskId());
            if (task == null || !isTerminal(task.getStatus())) {
                continue;
            }
            String status = "SUCCESS".equals(task.getStatus()) ? "SUCCESS" : "FAILED";
            updateMember(member, status, task.getId(), task.getError());
            changed = true;
        }
        if (changed) {
            refreshCounters(rollout);
        }
    }

    private void finalizeRollout(OtaRollout rollout) {
        refreshCounters(rollout);
        int total = rollout.getTotalCount() == null ? 0 : rollout.getTotalCount();
        int success = rollout.getSuccessCount() == null ? 0 : rollout.getSuccessCount();
        int failed = rollout.getFailedCount() == null ? 0 : rollout.getFailedCount();
        if (success == total) {
            rollout.setStatus("SUCCESS");
        } else if (success > 0) {
            rollout.setStatus("PARTIAL_FAILED");
        } else if (failed > 0) {
            rollout.setStatus("FAILED");
        } else {
            rollout.setStatus("FAILED");
        }
        rollout.setFinishedAt(OffsetDateTime.now());
        rollout.setUpdatedAt(OffsetDateTime.now());
        rolloutMapper.updateById(rollout);
        meterRegistry.counter("coherer.ota.rollout.finished",
                "result", rollout.getStatus().toLowerCase()).increment();
    }

    private void refreshCounters(OtaRollout rollout) {
        List<OtaRolloutDevice> members = rolloutDeviceMapper.selectList(
                Wrappers.<OtaRolloutDevice>lambdaQuery()
                        .select(OtaRolloutDevice::getStatus)
                        .eq(OtaRolloutDevice::getRolloutId, rollout.getId()));
        int pending = 0;
        int running = 0;
        int success = 0;
        int failed = 0;
        for (OtaRolloutDevice member : members) {
            switch (member.getStatus()) {
                case "PENDING" -> pending++;
                case "RUNNING" -> running++;
                case "SUCCESS" -> success++;
                case "FAILED" -> failed++;
                default -> {
                }
            }
        }
        rollout.setPendingCount(pending);
        rollout.setRunningCount(running);
        rollout.setSuccessCount(success);
        rollout.setFailedCount(failed);
        rollout.setUpdatedAt(OffsetDateTime.now());
        // 只更新计数，避免并发事件用旧对象覆盖状态/当前批次
        rolloutMapper.update(null, Wrappers.<OtaRollout>lambdaUpdate()
                .eq(OtaRollout::getId, rollout.getId())
                .set(OtaRollout::getPendingCount, pending)
                .set(OtaRollout::getRunningCount, running)
                .set(OtaRollout::getSuccessCount, success)
                .set(OtaRollout::getFailedCount, failed)
                .set(OtaRollout::getUpdatedAt, rollout.getUpdatedAt()));
    }

    private BatchStats batchStats(Long rolloutId, int batchNo) {
        List<OtaRolloutDevice> members = rolloutDeviceMapper.selectList(
                Wrappers.<OtaRolloutDevice>lambdaQuery()
                        .select(OtaRolloutDevice::getStatus)
                        .eq(OtaRolloutDevice::getRolloutId, rolloutId)
                        .eq(OtaRolloutDevice::getBatchNo, batchNo)
                        .in(OtaRolloutDevice::getStatus, List.of("SUCCESS", "FAILED")));
        int success = 0;
        int failed = 0;
        for (OtaRolloutDevice member : members) {
            if ("SUCCESS".equals(member.getStatus())) {
                success++;
            } else {
                failed++;
            }
        }
        return new BatchStats(success, failed);
    }

    private long countMembers(Long rolloutId, String status) {
        Long count = rolloutDeviceMapper.selectCount(Wrappers.<OtaRolloutDevice>lambdaQuery()
                .eq(OtaRolloutDevice::getRolloutId, rolloutId)
                .eq(OtaRolloutDevice::getStatus, status));
        return count == null ? 0 : count;
    }

    private OtaRollout requireVisible(Long id, DataScope scope, String username) {
        OtaRollout rollout = rolloutMapper.selectById(id);
        if (rollout == null) {
            throw new BizException(404, "灰度批次不存在");
        }
        if (!scope.unrestricted() && !username.equals(rollout.getCreatedBy())) {
            throw new BizException(403, "无权查看该灰度批次");
        }
        return rollout;
    }

    private OtaRollout requireVisibleForUpdate(Long id, DataScope scope, String username) {
        OtaRollout rollout = rolloutMapper.selectForUpdate(id);
        if (rollout == null) {
            throw new BizException(404, "灰度批次不存在");
        }
        if (!scope.unrestricted() && !username.equals(rollout.getCreatedBy())) {
            throw new BizException(403, "无权操作该灰度批次");
        }
        return rollout;
    }

    private Map<Long, OtaTask> tasksByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectBatchIds(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(OtaTask::getId, Function.identity()));
    }

    private Map<String, Device> devicesByBizId(List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                        .in(Device::getDeviceId, deviceIds.stream().distinct().toList()))
                .stream()
                .collect(Collectors.toMap(Device::getDeviceId, Function.identity()));
    }

    private Map<Long, Firmware> firmwareMap(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return firmwareMapper.selectBatchIds(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Firmware::getId, Function.identity()));
    }

    private OtaRolloutVO toVO(OtaRollout rollout, Firmware firmware,
                              List<OtaRolloutDevice> members) {
        OtaRolloutVO vo = new OtaRolloutVO();
        BeanUtils.copyProperties(rollout, vo);
        if (firmware != null) {
            vo.setFirmwareVersion(firmware.getVersion());
        }
        int total = rollout.getTotalCount() == null ? 0 : rollout.getTotalCount();
        int batchSize = rollout.getBatchSize() == null || rollout.getBatchSize() <= 0
                ? 1 : rollout.getBatchSize();
        vo.setTotalBatches((int) Math.ceil((double) total / batchSize));
        if (members != null) {
            vo.setSkippedCount(members.stream()
                    .filter(member -> "SKIPPED".equals(member.getStatus()))
                    .toList().size());
        } else {
            int accounted = nullSafe(rollout.getPendingCount()) + nullSafe(rollout.getRunningCount())
                    + nullSafe(rollout.getSuccessCount()) + nullSafe(rollout.getFailedCount());
            vo.setSkippedCount(Math.max(total - accounted, 0));
        }
        return vo;
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private record BatchStats(int success, int failed) {

        int finished() {
            return success + failed;
        }
    }
}

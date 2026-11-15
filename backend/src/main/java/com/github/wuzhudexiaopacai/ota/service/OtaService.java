package com.github.wuzhudexiaopacai.ota.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.service.HostLookupService;
import com.github.wuzhudexiaopacai.ota.dto.OtaStartRequest;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.event.OtaTaskFinishedEvent;
import com.github.wuzhudexiaopacai.ota.mapper.FirmwareMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import com.github.wuzhudexiaopacai.ota.vo.OtaTaskVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** OTA 任务：设备/主机路由、状态机与超时巡检。 */
@Service
@RequiredArgsConstructor
public class OtaService extends ServiceImpl<OtaTaskMapper, OtaTask> {

    private static final Logger log = LoggerFactory.getLogger(OtaService.class);

    private final FirmwareService firmwareService;
    private final FirmwareMapper firmwareMapper;
    private final DeviceLookupService deviceLookupService;
    private final HostLookupService hostLookupService;
    private final OtaPublisher otaPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${coherer.ota.task-timeout-seconds:600}")
    private long taskTimeoutSeconds;

    @Value("${coherer.ota.retry-backoff-seconds:30}")
    private long retryBackoffSeconds;

    /**
     * 创建并下发 OTA 任务。
     *
     * <p>刻意不包大事务：任务行先提交，再产生 MQTT 副作用。否则设备可能在事务提交前
     * 就回报进度（查不到任务而丢弃），或 MQTT 已下发但事务回滚导致平台与设备状态不一致。</p>
     */
    public OtaTaskVO start(OtaStartRequest request, DataScope scope, String operator) {
        Firmware firmware = firmwareService.require(request.getFirmwareId());
        Device device = deviceLookupService.getByDeviceId(request.getDeviceId());
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        if (!scope.allows(device.getSpaceId())) {
            throw new BizException(403, "无权操作该设备");
        }
        if ("DISABLED".equals(device.getStatus())) {
            throw new BizException(409, "设备已禁用");
        }
        if (!firmware.getProductId().equals(device.getProductId())) {
            throw new BizException(409, "固件与设备产品不匹配");
        }
        int maxAttempts = request.getMaxAttempts() == null ? 1 : request.getMaxAttempts();
        OtaTask task = dispatch(firmware, device, operator, null, null, maxAttempts);
        return toVO(task, firmware);
    }

    /**
     * 供灰度批次调度使用：调用方已完成目标与数据范围校验。
     * 返回落库后的任务；可重试的失败会回到 PENDING，等待重试调度。
     */
    public OtaTaskDispatch startForDevice(Firmware firmware, Device device, String operator,
                                          Long rolloutId, Integer batchNo, int maxAttempts) {
        if ("DISABLED".equals(device.getStatus())) {
            throw new BizException(409, "设备已禁用");
        }
        if (!firmware.getProductId().equals(device.getProductId())) {
            throw new BizException(409, "固件与设备产品不匹配");
        }
        OtaTask task = dispatch(firmware, device, operator, rolloutId, batchNo, maxAttempts);
        return new OtaTaskDispatch(task.getId(), task.getStatus(), task.getError());
    }

    private OtaTask dispatch(Firmware firmware, Device device, String operator,
                             Long rolloutId, Integer batchNo, int maxAttempts) {
        Long active = baseMapper.selectCount(Wrappers.<OtaTask>lambdaQuery()
                .eq(OtaTask::getDeviceId, device.getDeviceId())
                .in(OtaTask::getStatus, List.of("PENDING", "SENT", "DOWNLOADING")));
        if (active != null && active > 0) {
            // 灰度调度重试/崩溃恢复：同一批次的进行中任务视为幂等，直接复用
            if (rolloutId != null) {
                OtaTask existing = baseMapper.selectOne(Wrappers.<OtaTask>lambdaQuery()
                        .eq(OtaTask::getDeviceId, device.getDeviceId())
                        .eq(OtaTask::getRolloutId, rolloutId)
                        .eq(OtaTask::getBatchNo, batchNo)
                        .in(OtaTask::getStatus, List.of("PENDING", "SENT", "DOWNLOADING"))
                        .last("limit 1"));
                if (existing != null) {
                    return existing;
                }
            }
            throw new BizException(409, "该设备已有进行中的升级任务");
        }

        String tenantId = StringUtils.hasText(device.getTenantId())
                ? device.getTenantId() : "default";
        OffsetDateTime now = OffsetDateTime.now();
        OtaTask task = new OtaTask();
        task.setTenantId(tenantId);
        task.setFirmwareId(firmware.getId());
        task.setDeviceId(device.getDeviceId());
        task.setRolloutId(rolloutId);
        task.setBatchNo(batchNo);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setAttempt(1);
        task.setMaxAttempts(Math.max(1, maxAttempts));
        task.setCreatedBy(operator);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            baseMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发启动时由唯一部分索引兜底
            throw new BizException(409, "该设备已有进行中的升级任务");
        }

        String hostKey = null;
        if (device.getHostId() != null) {
            IotHost host = hostLookupService.getById(device.getHostId());
            if (host == null || "DISABLED".equals(host.getStatus())) {
                failOrRetry(task, "设备所属主机不可用", "FAILED");
                return task;
            }
            hostKey = host.getHostId();
        }

        boolean published;
        try {
            String url = firmwareService.downloadUrl(firmware, device.getDeviceId());
            published = otaPublisher.publishOta(tenantId, device.getDeviceId(),
                    hostKey, task.getId(), firmware.getId(), firmware.getVersion(), url,
                    firmware.getSha256(), firmware.getSizeBytes(), task.getAttempt());
        } catch (Exception e) {
            log.warn("OTA 任务下发异常 taskId={} err={}", task.getId(), e.getMessage());
            failOrRetry(task, "OTA 指令生成或下发失败", "FAILED");
            return task;
        }
        OffsetDateTime sentAt = OffsetDateTime.now();
        if (published) {
            int updated = baseMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                    .eq(OtaTask::getId, task.getId())
                    .eq(OtaTask::getStatus, "PENDING")
                    .set(OtaTask::getStatus, "SENT")
                    .set(OtaTask::getSentAt, sentAt)
                    .set(OtaTask::getUpdatedAt, sentAt));
            if (updated > 0) {
                task.setStatus("SENT");
                task.setSentAt(sentAt);
            } else {
                OtaTask latest = getById(task.getId());
                if (latest != null) {
                    task = latest;
                }
            }
            meterRegistry.counter("coherer.ota.dispatched").increment();
        } else {
            failOrRetry(task, "MQTT 通道未连接，OTA 指令未下发", "FAILED");
            meterRegistry.counter("coherer.ota.dispatched", "result", "failed").increment();
        }
        return task;
    }

    public List<OtaTaskVO> recent(String deviceId, int limit, DataScope scope) {
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        if (!scope.allows(device.getSpaceId())) {
            throw new BizException(403, "无权查看该设备");
        }
        int size = Math.min(Math.max(limit, 1), 100);
        List<OtaTask> tasks = list(Wrappers.<OtaTask>lambdaQuery()
                .eq(OtaTask::getDeviceId, deviceId)
                .orderByDesc(OtaTask::getCreatedAt)
                .last("limit " + size));
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, Firmware> firmwareMap = firmwareMapper.selectBatchIds(
                        tasks.stream().map(OtaTask::getFirmwareId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Firmware::getId, Function.identity()));
        return tasks.stream().map(task -> toVO(task, firmwareMap.get(task.getFirmwareId())))
                .toList();
    }

    /** 长时间无进度的任务：可重试则回到 PENDING，否则终态 TIMEOUT。 */
    @Scheduled(fixedDelayString = "${coherer.ota.timeout-sweep-interval-ms:30000}")
    public void sweepTimeouts() {
        OffsetDateTime deadline = OffsetDateTime.now().minusSeconds(taskTimeoutSeconds);
        try {
            List<OtaTask> expired = new java.util.ArrayList<>();
            expired.addAll(baseMapper.selectList(Wrappers.<OtaTask>lambdaQuery()
                    .eq(OtaTask::getStatus, "SENT")
                    .lt(OtaTask::getSentAt, deadline)
                    .last("limit 200")));
            expired.addAll(baseMapper.selectList(Wrappers.<OtaTask>lambdaQuery()
                    .eq(OtaTask::getStatus, "DOWNLOADING")
                    .lt(OtaTask::getUpdatedAt, deadline)
                    .last("limit 200")));
            int updated = 0;
            for (OtaTask task : expired) {
                String error = "SENT".equals(task.getStatus())
                        ? "设备未在超时时间内完成升级"
                        : "升级进度长时间未更新";
                failOrRetry(task, error, "TIMEOUT");
                updated++;
            }
            if (updated > 0) {
                log.info("OTA 超时巡检：处理 {} 条超时任务（含重试）", updated);
            }
        } catch (Exception e) {
            log.warn("OTA 超时巡检失败: {}", e.getMessage());
        }
    }

    /** 重试到期的 PENDING 任务，以及进程崩溃遗留的长时间 PENDING。 */
    @Scheduled(fixedDelayString = "${coherer.ota.retry-sweep-interval-ms:10000}",
            initialDelayString = "${coherer.ota.retry-sweep-initial-delay-ms:15000}")
    public void retryDueTasks() {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            List<OtaTask> due = baseMapper.selectList(Wrappers.<OtaTask>lambdaQuery()
                    .eq(OtaTask::getStatus, "PENDING")
                    .isNotNull(OtaTask::getNextAttemptAt)
                    .le(OtaTask::getNextAttemptAt, now)
                    .orderByAsc(OtaTask::getNextAttemptAt)
                    .last("limit 50"));
            for (OtaTask task : due) {
                redispatch(task, now, true);
            }
            List<OtaTask> stale = baseMapper.selectList(Wrappers.<OtaTask>lambdaQuery()
                    .eq(OtaTask::getStatus, "PENDING")
                    .isNull(OtaTask::getNextAttemptAt)
                    .lt(OtaTask::getUpdatedAt, now.minusSeconds(120))
                    .orderByAsc(OtaTask::getUpdatedAt)
                    .last("limit 50"));
            for (OtaTask task : stale) {
                redispatch(task, now, false);
            }
        } catch (Exception e) {
            log.warn("OTA 重试巡检失败: {}", e.getMessage());
        }
    }

    private void redispatch(OtaTask task, OffsetDateTime now, boolean hasNextAttempt) {
        var claim = Wrappers.<OtaTask>lambdaUpdate()
                .eq(OtaTask::getId, task.getId())
                .eq(OtaTask::getStatus, "PENDING")
                .eq(OtaTask::getAttempt, task.getAttempt());
        if (hasNextAttempt) {
            claim.eq(OtaTask::getNextAttemptAt, task.getNextAttemptAt());
        } else {
            claim.isNull(OtaTask::getNextAttemptAt)
                    .lt(OtaTask::getUpdatedAt, now.minusSeconds(120));
        }
        claim.set(OtaTask::getNextAttemptAt, null)
                .set(OtaTask::getUpdatedAt, now);
        // 先抢占，保证多实例下同一任务只有一个实例重发 MQTT
        if (baseMapper.update(null, claim) == 0) {
            return;
        }
        task.setNextAttemptAt(null);
        task.setUpdatedAt(now);

        Firmware firmware = firmwareMapper.selectById(task.getFirmwareId());
        Device device = deviceLookupService.getByDeviceId(task.getDeviceId());
        if (firmware == null || device == null || "DISABLED".equals(device.getStatus())) {
            failOrRetry(task, device == null ? "设备不存在" : "设备已禁用", "FAILED");
            return;
        }
        String hostKey = null;
        if (device.getHostId() != null) {
            IotHost host = hostLookupService.getById(device.getHostId());
            if (host == null || "DISABLED".equals(host.getStatus())) {
                failOrRetry(task, "设备所属主机不可用", "FAILED");
                return;
            }
            hostKey = host.getHostId();
        }
        boolean published;
        try {
            String url = firmwareService.downloadUrl(firmware, device.getDeviceId());
            published = otaPublisher.publishOta(task.getTenantId(), device.getDeviceId(),
                    hostKey, task.getId(), firmware.getId(), firmware.getVersion(), url,
                    firmware.getSha256(), firmware.getSizeBytes(), task.getAttempt());
        } catch (Exception e) {
            failOrRetry(task, "OTA 指令生成或下发失败", "FAILED");
            return;
        }
        if (!published) {
            failOrRetry(task, "MQTT 通道未连接，OTA 指令未下发", "FAILED");
            return;
        }
        OffsetDateTime sentAt = OffsetDateTime.now();
        int updated = baseMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                .eq(OtaTask::getId, task.getId())
                .eq(OtaTask::getStatus, "PENDING")
                .eq(OtaTask::getAttempt, task.getAttempt())
                .set(OtaTask::getStatus, "SENT")
                .set(OtaTask::getSentAt, sentAt)
                .set(OtaTask::getUpdatedAt, sentAt)
                .set(OtaTask::getNextAttemptAt, null));
        if (updated > 0) {
            task.setStatus("SENT");
            task.setSentAt(sentAt);
            meterRegistry.counter("coherer.ota.retried").increment();
            log.info("OTA 重试已下发 deviceId={} taskId={} attempt={}/{}",
                    task.getDeviceId(), task.getId(), task.getAttempt(), task.getMaxAttempts());
        }
    }

    /**
     * 失败处理：还有重试次数则回到 PENDING 并安排退避；否则落终态并通知灰度。
     *
     * @param terminalStatus FAILED（设备/通道失败）或 TIMEOUT（超时）
     */
    private void failOrRetry(OtaTask task, String error, String terminalStatus) {
        String fromStatus = task.getStatus();
        int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
        OffsetDateTime now = OffsetDateTime.now();
        if (OtaRetryPolicy.canRetry(task)) {
            int nextAttempt = attempt + 1;
            OffsetDateTime nextAt = now.plusSeconds(
                    OtaRetryPolicy.backoffSeconds(nextAttempt, retryBackoffSeconds));
            int updated = baseMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                    .eq(OtaTask::getId, task.getId())
                    .eq(OtaTask::getStatus, fromStatus)
                    .eq(OtaTask::getAttempt, attempt)
                    .set(OtaTask::getStatus, "PENDING")
                    .set(OtaTask::getAttempt, nextAttempt)
                    .set(OtaTask::getError, error)
                    .set(OtaTask::getNextAttemptAt, nextAt)
                    .set(OtaTask::getUpdatedAt, now)
                    .set(OtaTask::getFinishedAt, null));
            if (updated > 0) {
                task.setStatus("PENDING");
                task.setAttempt(nextAttempt);
                task.setError(error);
                task.setNextAttemptAt(nextAt);
                task.setUpdatedAt(now);
                task.setFinishedAt(null);
                meterRegistry.counter("coherer.ota.retry_scheduled").increment();
                log.info("OTA 失败，{} 秒后重试 deviceId={} taskId={} attempt={}/{}",
                        OtaRetryPolicy.backoffSeconds(nextAttempt, retryBackoffSeconds),
                        task.getDeviceId(), task.getId(), nextAttempt, task.getMaxAttempts());
            }
            return;
        }

        int updated = baseMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                .eq(OtaTask::getId, task.getId())
                .eq(OtaTask::getStatus, fromStatus)
                .eq(OtaTask::getAttempt, attempt)
                .set(OtaTask::getStatus, terminalStatus)
                .set(OtaTask::getError, error)
                .set(OtaTask::getNextAttemptAt, null)
                .set(OtaTask::getUpdatedAt, now)
                .set(OtaTask::getFinishedAt, now));
        if (updated == 0) {
            return;
        }
        task.setStatus(terminalStatus);
        task.setError(error);
        task.setNextAttemptAt(null);
        task.setUpdatedAt(now);
        task.setFinishedAt(now);
        if ("TIMEOUT".equals(terminalStatus)) {
            meterRegistry.counter("coherer.ota.timeout").increment();
        }
        if (task.getRolloutId() != null) {
            eventPublisher.publishEvent(new OtaTaskFinishedEvent(task.getId(),
                    task.getRolloutId(), task.getDeviceId(), terminalStatus, error));
        }
    }

    /** 灰度调度只关心任务是否落库、当前状态与失败原因。 */
    public record OtaTaskDispatch(Long taskId, String status, String error) {
    }

    private OtaTaskVO toVO(OtaTask task, Firmware firmware) {
        OtaTaskVO vo = new OtaTaskVO();
        BeanUtils.copyProperties(task, vo);
        if (firmware != null) {
            vo.setFirmwareVersion(firmware.getVersion());
        }
        return vo;
    }
}

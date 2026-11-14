package com.github.wuzhudexiaopacai.command.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.command.dto.CommandSendRequest;
import com.github.wuzhudexiaopacai.command.entity.DeviceCommand;
import com.github.wuzhudexiaopacai.command.mapper.DeviceCommandMapper;
import com.github.wuzhudexiaopacai.command.vo.CommandVO;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备服务调用：保存命令、下发 MQTT、维护 PENDING→SENT→SUCCESS/FAILED/TIMEOUT 状态机。
 *
 * <p>回执由 {@link CommandReplyService} 处理，两者通过 requestId 关联；超时由定时任务兜底。</p>
 */
@Service
@RequiredArgsConstructor
public class CommandService extends ServiceImpl<DeviceCommandMapper, DeviceCommand> {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final CommandPublisher commandPublisher;
    private final DeviceLookupService deviceLookupService;
    private final MeterRegistry meterRegistry;

    @Value("${coherer.command.reply-timeout-seconds:30}")
    private long replyTimeoutSeconds;

    @Transactional(rollbackFor = Exception.class)
    public CommandVO send(String deviceId, CommandSendRequest request, String operator) {
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        if ("DISABLED".equals(device.getStatus())) {
            throw new BizException(409, "设备已禁用，无法下发命令");
        }

        String requestId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> params = request.getParams() == null
                ? Map.of() : request.getParams();

        DeviceCommand command = new DeviceCommand();
        command.setTenantId(device.getTenantId());
        command.setDeviceId(deviceId);
        command.setProductId(device.getProductId());
        command.setIdentifier(request.getIdentifier());
        command.setRequestId(requestId);
        command.setParams(params);
        command.setStatus("PENDING");
        command.setOperator(operator);
        command.setCreatedAt(OffsetDateTime.now());
        save(command);

        boolean published = commandPublisher.publishCommand(
                device.getTenantId(), deviceId, request.getIdentifier(), requestId, params);
        OffsetDateTime sentAt = OffsetDateTime.now();
        if (published) {
            // 条件更新：设备回执可能先于本次更新到达，绝不能把 SUCCESS 覆盖回 SENT
            int updated = baseMapper.update(null, Wrappers.<DeviceCommand>lambdaUpdate()
                    .eq(DeviceCommand::getId, command.getId())
                    .eq(DeviceCommand::getStatus, "PENDING")
                    .set(DeviceCommand::getStatus, "SENT")
                    .set(DeviceCommand::getSentAt, sentAt));
            if (updated > 0) {
                command.setStatus("SENT");
                command.setSentAt(sentAt);
            } else {
                command = reload(command);
            }
            meterRegistry.counter("coherer.command.dispatched").increment();
        } else {
            int updated = baseMapper.update(null, Wrappers.<DeviceCommand>lambdaUpdate()
                    .eq(DeviceCommand::getId, command.getId())
                    .eq(DeviceCommand::getStatus, "PENDING")
                    .set(DeviceCommand::getStatus, "FAILED")
                    .set(DeviceCommand::getError, "MQTT 通道未连接，命令未下发"));
            if (updated > 0) {
                command.setStatus("FAILED");
                command.setError("MQTT 通道未连接，命令未下发");
            } else {
                command = reload(command);
            }
            meterRegistry.counter("coherer.command.dispatched", "result", "failed").increment();
        }
        return toVO(command);
    }

    private DeviceCommand reload(DeviceCommand fallback) {
        DeviceCommand latest = getById(fallback.getId());
        return latest == null ? fallback : latest;
    }

    /** 最近命令，按创建时间倒序。 */
    public List<CommandVO> recent(String deviceId, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        return list(Wrappers.<DeviceCommand>lambdaQuery()
                .eq(DeviceCommand::getDeviceId, deviceId)
                .orderByDesc(DeviceCommand::getCreatedAt)
                .last("limit " + size))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /** 超时未回执的命令置为 TIMEOUT，避免永久 SENT / PENDING。 */
    @Scheduled(fixedDelayString = "${coherer.command.timeout-sweep-interval-ms:15000}")
    public void sweepTimeouts() {
        OffsetDateTime deadline = OffsetDateTime.now().minusSeconds(replyTimeoutSeconds);
        try {
            // 正常下发后未回执
            int sent = baseMapper.update(null, Wrappers.<DeviceCommand>lambdaUpdate()
                    .eq(DeviceCommand::getStatus, "SENT")
                    .lt(DeviceCommand::getSentAt, deadline)
                    .set(DeviceCommand::getStatus, "TIMEOUT")
                    .set(DeviceCommand::getError, "设备未在超时时间内回执"));
            // 进程在保存后、发布前崩溃会留下 PENDING，同样需要兜底
            int pending = baseMapper.update(null, Wrappers.<DeviceCommand>lambdaUpdate()
                    .eq(DeviceCommand::getStatus, "PENDING")
                    .lt(DeviceCommand::getCreatedAt, deadline)
                    .set(DeviceCommand::getStatus, "TIMEOUT")
                    .set(DeviceCommand::getError, "命令未成功下发（服务重启或发布失败）"));
            int updated = sent + pending;
            if (updated > 0) {
                meterRegistry.counter("coherer.command.timeout").increment(updated);
                log.info("命令超时巡检：{} 条置为 TIMEOUT", updated);
            }
        } catch (Exception e) {
            log.warn("命令超时巡检失败: {}", e.getMessage());
        }
    }

    private CommandVO toVO(DeviceCommand command) {
        CommandVO vo = new CommandVO();
        BeanUtils.copyProperties(command, vo);
        return vo;
    }
}

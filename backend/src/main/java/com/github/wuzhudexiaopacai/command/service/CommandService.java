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
import com.github.wuzhudexiaopacai.thingmodel.entity.ThingModel;
import com.github.wuzhudexiaopacai.thingmodel.service.ThingModelService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 设备服务调用：保存命令、下发 MQTT、维护 PENDING→SENT→SUCCESS/FAILED/TIMEOUT 状态机。
 *
 * <p>回执由 {@link CommandReplyService} 处理，两者通过 requestId 关联；超时由定时任务兜底。</p>
 */
@Service
@RequiredArgsConstructor
public class CommandService extends ServiceImpl<DeviceCommandMapper, DeviceCommand> {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private static final String RATE_KEY_PREFIX = "coherer:command:rate:";

    private final CommandPublisher commandPublisher;
    private final DeviceLookupService deviceLookupService;
    private final ThingModelService thingModelService;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${coherer.command.reply-timeout-seconds:30}")
    private long replyTimeoutSeconds;

    @Value("${coherer.command.rate-limit-per-minute:30}")
    private long rateLimitPerMinute;

    @Transactional(rollbackFor = Exception.class)
    public CommandVO send(String deviceId, CommandSendRequest request, String operator) {
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        if ("DISABLED".equals(device.getStatus())) {
            throw new BizException(409, "设备已禁用，无法下发命令");
        }
        String clientRequestId = StringUtils.hasText(request.getClientRequestId())
                ? request.getClientRequestId() : null;
        if (clientRequestId != null) {
            DeviceCommand existing = findByClientRequestId(deviceId, clientRequestId);
            if (existing != null) {
                return toVO(existing);
            }
        }
        validateService(device, request);
        checkRateLimit(deviceId);

        String requestId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> params = request.getParams() == null
                ? Map.of() : request.getParams();

        DeviceCommand command = new DeviceCommand();
        command.setTenantId(device.getTenantId());
        command.setDeviceId(deviceId);
        command.setProductId(device.getProductId());
        command.setIdentifier(request.getIdentifier());
        command.setRequestId(requestId);
        command.setClientRequestId(clientRequestId);
        command.setParams(params);
        command.setStatus("PENDING");
        command.setOperator(operator);
        command.setCreatedAt(OffsetDateTime.now());
        try {
            save(command);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一索引兜底，返回已存在的那条
            DeviceCommand existing = findByClientRequestId(deviceId, clientRequestId);
            if (existing != null) {
                return toVO(existing);
            }
            throw e;
        }

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

    private DeviceCommand findByClientRequestId(String deviceId, String clientRequestId) {
        if (clientRequestId == null) {
            return null;
        }
        return list(Wrappers.<DeviceCommand>lambdaQuery()
                .eq(DeviceCommand::getDeviceId, deviceId)
                .eq(DeviceCommand::getClientRequestId, clientRequestId)
                .last("limit 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void validateService(Device device, CommandSendRequest request) {
        List<ThingModel> services = thingModelService.servicesCached(device.getProductId());
        if (services.isEmpty()) {
            // 兼容未定义物模型的早期产品；定义了服务后必须严格校验
            log.debug("产品未定义服务，跳过命令校验 productId={}", device.getProductId());
            return;
        }
        ThingModel definition = services.stream()
                .filter(service -> service.getIdentifier().equals(request.getIdentifier()))
                .findFirst()
                .orElseThrow(() -> new BizException(400,
                        "服务 " + request.getIdentifier() + " 未在物模型中定义"));
        Map<String, Object> spec = definition.getSpec();
        Object input = spec == null ? null : spec.get("input");
        if (!(input instanceof Map<?, ?> inputs)) {
            return;
        }
        Map<String, Object> params = request.getParams() == null ? Map.of() : request.getParams();
        for (String key : params.keySet()) {
            if (!inputs.containsKey(key)) {
                throw new BizException(400, "服务参数 " + key + " 未在物模型中定义");
            }
        }
        for (Map.Entry<?, ?> entry : inputs.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!params.containsKey(key)) {
                continue;
            }
            validateParamType(key, String.valueOf(entry.getValue()), params.get(key));
        }
    }

    private void validateParamType(String key, String type, Object value) {
        if (value == null) {
            throw new BizException(400, "服务参数 " + key + " 不能为空");
        }
        switch (type.toUpperCase()) {
            case "INT" -> {
                if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                    throw new BizException(400, "服务参数 " + key + " 需要整数");
                }
            }
            case "FLOAT", "DOUBLE" -> {
                if (!(value instanceof Number)) {
                    throw new BizException(400, "服务参数 " + key + " 需要数值");
                }
            }
            case "BOOL" -> {
                if (!(value instanceof Boolean)) {
                    throw new BizException(400, "服务参数 " + key + " 需要布尔值");
                }
            }
            case "STRING" -> {
                if (!(value instanceof String)) {
                    throw new BizException(400, "服务参数 " + key + " 需要字符串");
                }
            }
            default -> {
                // 未知类型定义放行，避免物模型扩展字段导致误拒
            }
        }
    }

    private void checkRateLimit(String deviceId) {
        String key = RATE_KEY_PREFIX + deviceId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            if (count != null && count > rateLimitPerMinute) {
                meterRegistry.counter("coherer.command.rate_limited").increment();
                throw new BizException(429, "命令发送过于频繁，请稍后再试");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // Redis 故障不阻塞命令下发
            meterRegistry.counter("coherer.command.rate_limit_error").increment();
            log.warn("命令限流检查失败（放行） deviceId={} err={}", deviceId, e.getMessage());
        }
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

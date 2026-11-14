package com.github.wuzhudexiaopacai.event.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "device_event", autoResultMap = true)
public class DeviceEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private String deviceId;

    private Long productId;

    private String identifier;

    /** 设备侧事件 ID，用于幂等 */
    private String eventId;

    /** 设备事件时间（epoch millis） */
    private Long ts;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    private OffsetDateTime createdAt;
}

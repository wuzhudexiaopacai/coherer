package com.github.wuzhudexiaopacai.command.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "device_command", autoResultMap = true)
public class DeviceCommand {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private String deviceId;

    private Long productId;

    private String identifier;

    private String requestId;

    /** 客户端幂等键（可选） */
    private String clientRequestId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    /** PENDING / SENT / SUCCESS / FAILED / TIMEOUT */
    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> result;

    private String error;

    private String operator;

    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;

    private OffsetDateTime repliedAt;
}

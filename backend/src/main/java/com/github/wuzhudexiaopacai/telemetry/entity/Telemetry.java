package com.github.wuzhudexiaopacai.telemetry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "telemetry", autoResultMap = true)
public class Telemetry {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long productId;

    private String deviceId;

    /** 设备上报时间 */
    private OffsetDateTime ts;

    /** 物模型属性键值，{"temperature":23.5,...} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    private OffsetDateTime receivedAt;
}

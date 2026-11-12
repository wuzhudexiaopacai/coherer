package com.github.wuzhudexiaopacai.shadow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "device_shadow", autoResultMap = true)
public class DeviceShadow {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private String deviceId;

    /** 期望状态（云端下发，设备同步后生效） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> desired;

    /** 上报状态（设备实际状态） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> reported;

    /** 乐观锁版本号 */
    @Version
    private Long version;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}

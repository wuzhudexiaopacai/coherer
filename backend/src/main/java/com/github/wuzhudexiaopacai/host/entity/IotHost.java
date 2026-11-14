package com.github.wuzhudexiaopacai.host.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("iot_host")
public class IotHost {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 主机业务标识（MQTT clientId） */
    private String hostId;

    private String name;

    private Long spaceId;

    private String model;

    private String firmwareVersion;

    private String secretCipher;

    /** INACTIVE / ONLINE / OFFLINE / DISABLED */
    private String status;

    private OffsetDateTime lastOnlineAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

package com.github.wuzhudexiaopacai.device.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("device")
public class Device {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long productId;

    /** 设备归属空间 */
    private Long spaceId;

    /** 所属边缘主机；NULL 表示 Wi-Fi/以太网设备直连 */
    private Long hostId;

    /** 设备唯一标识（MQTT clientId） */
    private String deviceId;

    private String name;

    /** 设备密钥密文（AES-256-GCM） */
    private String secretCipher;

    /** INACTIVE / ONLINE / OFFLINE / DISABLED */
    private String status;

    /** 设备最近一次上报/升级成功的固件版本 */
    private String firmwareVersion;

    private OffsetDateTime lastOnlineAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

package com.github.wuzhudexiaopacai.ota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("firmware")
public class Firmware {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long productId;

    private String version;

    private String fileName;

    private String contentType;

    private Long sizeBytes;

    private String sha256;

    private String storagePath;

    private String createdBy;

    private OffsetDateTime createdAt;

    @TableLogic
    private Integer deleted;
}

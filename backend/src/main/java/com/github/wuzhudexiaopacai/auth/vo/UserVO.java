package com.github.wuzhudexiaopacai.auth.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class UserVO {

    private Long id;

    private String tenantId;

    private Boolean platformAdmin;

    private String username;

    private String nickname;

    private String role;

    /** 数据范围空间；null 表示全平台 */
    private Long spaceId;

    private String status;

    private OffsetDateTime createdAt;
}

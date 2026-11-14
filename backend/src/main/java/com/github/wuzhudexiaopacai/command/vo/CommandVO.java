package com.github.wuzhudexiaopacai.command.vo;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
public class CommandVO {

    private Long id;

    private String deviceId;

    private String identifier;

    private String requestId;

    private Map<String, Object> params;

    private String status;

    private Map<String, Object> result;

    private String error;

    private String operator;

    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;

    private OffsetDateTime repliedAt;
}

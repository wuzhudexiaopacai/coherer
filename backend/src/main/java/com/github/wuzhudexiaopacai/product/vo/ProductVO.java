package com.github.wuzhudexiaopacai.product.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class ProductVO {

    private Long id;

    private String productKey;

    private String name;

    private String category;

    private String nodeType;

    private String authType;

    private String status;

    private String description;

    private OffsetDateTime createdAt;
}

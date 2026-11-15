package com.github.wuzhudexiaopacai.space.vo;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class SpaceNodeVO {

    private Long id;

    private Long projectId;

    private Long parentId;

    private String name;

    private String code;

    private String type;

    private Integer sort;

    private String description;

    private String status;

    private OffsetDateTime createdAt;

    private List<SpaceNodeVO> children = new ArrayList<>();
}

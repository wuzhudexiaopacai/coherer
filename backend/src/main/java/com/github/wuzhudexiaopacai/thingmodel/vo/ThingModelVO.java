package com.github.wuzhudexiaopacai.thingmodel.vo;

import java.util.Map;
import lombok.Data;

@Data
public class ThingModelVO {

    private Long id;

    private String type;

    private String identifier;

    private String name;

    private String dataType;

    private String accessMode;

    private String unit;

    private Map<String, Object> spec;
}

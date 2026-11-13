package com.github.wuzhudexiaopacai.dashboard.vo;

/** 总览 KPI。 */
public record OverviewVO(
        long productTotal,
        long deviceTotal,
        long online,
        long offline,
        long inactive,
        long disabled,
        long telemetryTotal,
        long telemetryToday) {
}

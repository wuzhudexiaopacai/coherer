package com.github.wuzhudexiaopacai.dashboard.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.dashboard.service.DashboardService;
import com.github.wuzhudexiaopacai.dashboard.vo.OverviewVO;
import com.github.wuzhudexiaopacai.dashboard.vo.TrendPointVO;
import com.github.wuzhudexiaopacai.project.service.ProjectScopeService;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import com.github.wuzhudexiaopacai.telemetry.vo.TelemetryVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final ProjectScopeService projectScopeService;
    private final DataScopeService dataScopeService;

    @GetMapping("/overview")
    public ApiResponse<OverviewVO> overview(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long spaceId) {
        return ApiResponse.ok(dashboardService.overview(projectScopeService.resolveSpaceIds(
                dataScopeService.current(), projectId, spaceId)));
    }

    @GetMapping("/telemetry-trend")
    public ApiResponse<List<TrendPointVO>> telemetryTrend(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long spaceId) {
        return ApiResponse.ok(dashboardService.telemetryTrend(hours,
                projectScopeService.resolveSpaceIds(
                        dataScopeService.current(), projectId, spaceId)));
    }

    @GetMapping("/recent-telemetry")
    public ApiResponse<List<TelemetryVO>> recentTelemetry(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long spaceId) {
        int size = Math.min(Math.max(limit, 1), 50);
        return ApiResponse.ok(dashboardService.recentTelemetry(size,
                projectScopeService.resolveSpaceIds(
                        dataScopeService.current(), projectId, spaceId)));
    }
}

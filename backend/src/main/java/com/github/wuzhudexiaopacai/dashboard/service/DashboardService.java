package com.github.wuzhudexiaopacai.dashboard.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.dashboard.vo.OverviewVO;
import com.github.wuzhudexiaopacai.dashboard.vo.TrendPointVO;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import com.github.wuzhudexiaopacai.telemetry.TelemetryTdStore;
import com.github.wuzhudexiaopacai.telemetry.vo.TelemetryVO;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DeviceMapper deviceMapper;
    private final ProductMapper productMapper;
    private final SpaceService spaceService;
    private final TelemetryTdStore telemetryTdStore;
    private final JdbcTemplate tdJdbc;

    public DashboardService(DeviceMapper deviceMapper,
                            ProductMapper productMapper,
                            SpaceService spaceService,
                            TelemetryTdStore telemetryTdStore,
                            @Qualifier("tdengineJdbcTemplate") JdbcTemplate tdJdbc) {
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.spaceService = spaceService;
        this.telemetryTdStore = telemetryTdStore;
        this.tdJdbc = tdJdbc;
    }

    public OverviewVO overview(Long spaceId) {
        List<Long> spaceIds = spaceId == null ? null : spaceService.descendantIds(spaceId);
        if (spaceIds != null && spaceIds.isEmpty()) {
            return new OverviewVO(0, 0, 0, 0, 0, 0, 0, 0);
        }
        List<String> deviceIds = spaceId == null ? null : spaceService.deviceIdsInSpace(spaceId);
        long telemetryTotal = 0;
        long telemetryToday = 0;
        if (deviceIds == null || !deviceIds.isEmpty()) {
            Object[] args = deviceIds == null ? new Object[0] : deviceIds.toArray();
            String in = inClause(deviceIds);
            telemetryTotal = countTelemetry("SELECT COUNT(*) FROM coherer.stb_telemetry"
                    + (in.isEmpty() ? "" : " WHERE device_id IN (" + in + ")"), args);
            long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            telemetryToday = countTelemetry("SELECT COUNT(*) FROM coherer.stb_telemetry WHERE ts >= "
                    + todayStart + (in.isEmpty() ? "" : " AND device_id IN (" + in + ")"), args);
        }
        return new OverviewVO(
                productMapper.selectCount(null),
                countDevices(spaceIds, null),
                countDevices(spaceIds, "ONLINE"),
                countDevices(spaceIds, "OFFLINE"),
                countDevices(spaceIds, "INACTIVE"),
                countDevices(spaceIds, "DISABLED"),
                telemetryTotal,
                telemetryToday);
    }

    public List<TrendPointVO> telemetryTrend(int hours, Long spaceId) {
        int window = Math.min(Math.max(hours, 1), 168);
        long since = System.currentTimeMillis() - window * 3_600_000L;
        List<String> deviceIds = spaceId == null ? null : spaceService.deviceIdsInSpace(spaceId);
        if (deviceIds != null && deviceIds.isEmpty()) {
            return List.of();
        }
        String in = inClause(deviceIds);
        String sql = "SELECT _wstart AS win_start, COUNT(*) AS cnt FROM coherer.stb_telemetry "
                + "WHERE ts >= " + since
                + (in.isEmpty() ? "" : " AND device_id IN (" + in + ")")
                + " INTERVAL(1h)";
        try {
            if (deviceIds == null) {
                return tdJdbc.query(sql, (rs, rowNum) -> new TrendPointVO(
                        rs.getTimestamp("win_start").toInstant().toString(),
                        rs.getLong("cnt")));
            }
            return tdJdbc.query(sql, (rs, rowNum) -> new TrendPointVO(
                    rs.getTimestamp("win_start").toInstant().toString(),
                    rs.getLong("cnt")), deviceIds.toArray());
        } catch (Exception e) {
            log.warn("TDengine 趋势查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<TelemetryVO> recentTelemetry(int limit, Long spaceId) {
        List<String> deviceIds = spaceId == null ? null : spaceService.deviceIdsInSpace(spaceId);
        return telemetryTdStore.recentAll(limit, deviceIds);
    }

    private long countDevices(List<Long> spaceIds, String status) {
        Long count = deviceMapper.selectCount(Wrappers.<Device>lambdaQuery()
                .in(spaceIds != null, Device::getSpaceId, spaceIds == null ? List.of() : spaceIds)
                .eq(status != null, Device::getStatus, status));
        return count == null ? 0 : count;
    }

    private long countTelemetry(String sql, Object[] args) {
        try {
            Long count = args.length == 0
                    ? tdJdbc.queryForObject(sql, Long.class)
                    : tdJdbc.queryForObject(sql, Long.class, args);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("TDengine 统计查询失败: {}", e.getMessage());
            return 0;
        }
    }

    private String inClause(List<String> deviceIds) {
        if (deviceIds == null) {
            return "";
        }
        return String.join(",", Collections.nCopies(deviceIds.size(), "?"));
    }
}

package com.github.wuzhudexiaopacai.dashboard.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.dashboard.vo.OverviewVO;
import com.github.wuzhudexiaopacai.dashboard.vo.TrendPointVO;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final JdbcTemplate tdJdbc;

    public DashboardService(DeviceMapper deviceMapper,
                            ProductMapper productMapper,
                            @Qualifier("tdengineJdbcTemplate") JdbcTemplate tdJdbc) {
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.tdJdbc = tdJdbc;
    }

    public OverviewVO overview() {
        long deviceTotal = deviceMapper.selectCount(null);
        long online = countStatus("ONLINE");
        long offline = countStatus("OFFLINE");
        long inactive = countStatus("INACTIVE");
        long disabled = countStatus("DISABLED");
        long productTotal = productMapper.selectCount(null);

        long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        return new OverviewVO(
                productTotal,
                deviceTotal,
                online,
                offline,
                inactive,
                disabled,
                countTelemetry("SELECT COUNT(*) FROM coherer.stb_telemetry"),
                countTelemetry("SELECT COUNT(*) FROM coherer.stb_telemetry WHERE ts >= " + todayStart));
    }

    public List<TrendPointVO> telemetryTrend(int hours) {
        int window = Math.min(Math.max(hours, 1), 168);
        long since = System.currentTimeMillis() - window * 3_600_000L;
        String sql = "SELECT _wstart AS win_start, COUNT(*) AS cnt FROM coherer.stb_telemetry "
                + "WHERE ts >= " + since + " INTERVAL(1h)";
        try {
            return tdJdbc.query(sql, (rs, rowNum) -> new TrendPointVO(
                    rs.getTimestamp("win_start").toInstant().toString(),
                    rs.getLong("cnt")));
        } catch (Exception e) {
            log.warn("TDengine 趋势查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private long countStatus(String status) {
        Long count = deviceMapper.selectCount(
                Wrappers.<Device>lambdaQuery().eq(Device::getStatus, status));
        return count == null ? 0 : count;
    }

    private long countTelemetry(String sql) {
        try {
            Long count = tdJdbc.queryForObject(sql, Long.class);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("TDengine 统计查询失败: {}", e.getMessage());
            return 0;
        }
    }
}

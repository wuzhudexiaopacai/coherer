package com.github.wuzhudexiaopacai.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.telemetry.vo.TelemetryVO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** TDengine 遥测查询：超级表 stb_telemetry，设备维度子表 d_{deviceId}。写入见 {@link TelemetryBatchWriter}。 */
@Component
public class TelemetryTdStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TelemetryTdStore(@Qualifier("tdengineJdbcTemplate") JdbcTemplate jdbc,
                            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<TelemetryVO> recent(String tenantId, String deviceId, int limit) {
        String sql = "SELECT ts, payload, device_id FROM coherer.stb_telemetry "
                + "WHERE tenant_id = ? AND device_id = ? ORDER BY ts DESC LIMIT " + limit;
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), tenantId, deviceId);
    }

    /** 租户内最近遥测（看板用）。 */
    public List<TelemetryVO> recentAll(String tenantId, int limit) {
        return recentAll(tenantId, limit, null);
    }

    /** 按空间设备集合过滤的最近遥测；deviceIds 为 null 表示租户内不过滤，空集合表示无设备。 */
    public List<TelemetryVO> recentAll(String tenantId, int limit, List<String> deviceIds) {
        if (deviceIds != null && deviceIds.isEmpty()) {
            return List.of();
        }
        String in = deviceIds == null
                ? ""
                : " AND device_id IN ("
                        + String.join(",", java.util.Collections.nCopies(deviceIds.size(), "?")) + ")";
        String sql = "SELECT ts, payload, device_id FROM coherer.stb_telemetry"
                + " WHERE tenant_id = ?" + in + " ORDER BY ts DESC LIMIT " + limit;
        Object[] args = new Object[(deviceIds == null ? 0 : deviceIds.size()) + 1];
        args[0] = tenantId;
        if (deviceIds != null) {
            for (int i = 0; i < deviceIds.size(); i++) {
                args[i + 1] = deviceIds.get(i);
            }
        }
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), args);
    }

    /** 按空间（含子空间）最近遥测：直接走 space_id tag，避免按设备拼大 IN。 */
    public List<TelemetryVO> recentBySpaces(String tenantId, int limit, List<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",",
                java.util.Collections.nCopies(spaceIds.size(), "?"));
        String sql = "SELECT ts, payload, device_id FROM coherer.stb_telemetry"
                + " WHERE tenant_id = ? AND space_id IN (" + in
                + ") ORDER BY ts DESC LIMIT " + limit;
        Object[] args = new Object[spaceIds.size() + 1];
        args[0] = tenantId;
        for (int i = 0; i < spaceIds.size(); i++) {
            args[i + 1] = spaceIds.get(i);
        }
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), args);
    }

    private TelemetryVO mapRow(ResultSet rs) throws SQLException {
        TelemetryVO vo = new TelemetryVO();
        vo.setDeviceId(rs.getString("device_id"));
        vo.setTs(OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(rs.getTimestamp("ts").getTime()), ZoneOffset.UTC));
        try {
            vo.setParams(objectMapper.readValue(
                    rs.getString("payload"), new TypeReference<Map<String, Object>>() {
                    }));
        } catch (Exception e) {
            vo.setParams(Map.of());
        }
        vo.setReceivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return vo;
    }
}

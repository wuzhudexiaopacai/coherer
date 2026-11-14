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

    public List<TelemetryVO> recent(String deviceId, int limit) {
        String sql = "SELECT ts, payload, device_id FROM coherer.stb_telemetry WHERE device_id = ? "
                + "ORDER BY ts DESC LIMIT " + limit;
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), deviceId);
    }

    /** 全平台最近遥测（看板用）。 */
    public List<TelemetryVO> recentAll(int limit) {
        return recentAll(limit, null);
    }

    /** 按空间设备集合过滤的最近遥测；deviceIds 为 null 表示不过滤，空集合表示无设备。 */
    public List<TelemetryVO> recentAll(int limit, List<String> deviceIds) {
        if (deviceIds != null && deviceIds.isEmpty()) {
            return List.of();
        }
        String in = deviceIds == null
                ? ""
                : " WHERE device_id IN ("
                        + String.join(",", java.util.Collections.nCopies(deviceIds.size(), "?")) + ")";
        String sql = "SELECT ts, payload, device_id FROM coherer.stb_telemetry"
                + in + " ORDER BY ts DESC LIMIT " + limit;
        if (deviceIds == null) {
            return jdbc.query(sql, (rs, rowNum) -> mapRow(rs));
        }
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), deviceIds.toArray());
    }

    /** 按空间（含子空间）最近遥测：直接走 space_id tag，避免按设备拼大 IN。 */
    public List<TelemetryVO> recentBySpaces(int limit, List<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",",
                java.util.Collections.nCopies(spaceIds.size(), "?"));
        String sql = "SELECT ts, payload, device_id FROM coherer.stb_telemetry"
                + " WHERE space_id IN (" + in + ") ORDER BY ts DESC LIMIT " + limit;
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), spaceIds.toArray());
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

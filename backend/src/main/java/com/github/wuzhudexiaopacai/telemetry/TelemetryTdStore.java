package com.github.wuzhudexiaopacai.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.telemetry.vo.TelemetryVO;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** TDengine 遥测读写：超级表 stb_telemetry，设备维度子表 d_{deviceId}。 */
@Component
public class TelemetryTdStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TelemetryTdStore(@Qualifier("tdengineJdbcTemplate") JdbcTemplate jdbc,
                            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(String tenantId, String deviceId, Long productId,
                       long tsMillis, Map<String, Object> params) throws Exception {
        String payload = objectMapper.writeValueAsString(params);
        String subTable = "d_" + deviceId.replaceAll("[^A-Za-z0-9_]", "_");
        String sql = "INSERT INTO coherer." + subTable
                + " USING coherer.stb_telemetry TAGS (?, ?, ?) (ts, payload) VALUES (?, ?)";
        jdbc.update(sql, deviceId, tenantId, productId, new Timestamp(tsMillis), payload);
    }

    public List<TelemetryVO> recent(String deviceId, int limit) {
        String sql = "SELECT ts, payload FROM coherer.stb_telemetry WHERE device_id = ? "
                + "ORDER BY ts DESC LIMIT " + limit;
        return jdbc.query(sql, (rs, rowNum) -> {
            TelemetryVO vo = new TelemetryVO();
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
        }, deviceId);
    }
}

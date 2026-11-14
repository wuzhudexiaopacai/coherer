package com.github.wuzhudexiaopacai.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 遥测批量写入器：把短时间窗口内的多条遥测合并成一次 TDengine 写入。
 *
 * <p>单条 INSERT 在高吞吐下会成为瓶颈；批量写入把 RTT 和提交开销摊薄，
 * 按子表分组后仍然使用「自动建子表 + TAGS」语法，语义与单条写入一致。</p>
 *
 * <p>批次失败时按单条重试一次，隔离坏数据；仍失败则计入 {@code result=error} 并记日志，
 * 不会因为一条异常丢弃整批。</p>
 */
@Component
public class TelemetryBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBatchWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TelemetryBatchWriter(@Qualifier("tdengineJdbcTemplate") JdbcTemplate jdbc,
                                ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 一条待写入的遥测。 */
    public record Row(String tenantId, String deviceId, Long productId, Long spaceId,
                      long tsMillis, Map<String, Object> params) {
    }

    /**
     * 批量写入。
     *
     * @return 成功写入条数
     */
    public int write(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Map<String, List<Row>> byDevice = new LinkedHashMap<>();
        for (Row row : rows) {
            byDevice.computeIfAbsent(row.deviceId(), key -> new ArrayList<>()).add(row);
        }
        int written = 0;
        for (List<Row> deviceRows : byDevice.values()) {
            written += writeDeviceBatch(deviceRows);
        }
        return written;
    }

    private int writeDeviceBatch(List<Row> rows) {
        if (rows.size() == 1) {
            return writeOne(rows.get(0)) ? 1 : 0;
        }
        Row first = rows.get(0);
        StringBuilder sql = new StringBuilder("INSERT INTO coherer.")
                .append(subTable(first.deviceId()))
                .append(" USING coherer.stb_telemetry TAGS (?, ?, ?, ?) (ts, payload) VALUES ");
        List<Object> args = new ArrayList<>(4 + rows.size() * 2);
        args.add(first.deviceId());
        args.add(first.tenantId());
        args.add(first.productId());
        args.add(first.spaceId());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i > 0) {
                sql.append(' ');
            }
            sql.append("(?, ?)");
            args.add(new Timestamp(row.tsMillis()));
            args.add(toPayload(row));
        }
        try {
            jdbc.update(sql.toString(), args.toArray());
            return rows.size();
        } catch (Exception e) {
            log.warn("批量写入失败，降级为单条重试 deviceId={} count={} err={}",
                    first.deviceId(), rows.size(), e.getMessage());
            int written = 0;
            for (Row row : rows) {
                if (writeOne(row)) {
                    written++;
                }
            }
            return written;
        }
    }

    /** @return true 表示写入成功 */
    public boolean writeOne(Row row) {
        try {
            String sql = "INSERT INTO coherer." + subTable(row.deviceId())
                    + " USING coherer.stb_telemetry TAGS (?, ?, ?, ?) (ts, payload) VALUES (?, ?)";
            jdbc.update(sql, row.deviceId(), row.tenantId(), row.productId(), row.spaceId(),
                    new Timestamp(row.tsMillis()), toPayload(row));
            return true;
        } catch (Exception e) {
            log.warn("遥测写入失败 deviceId={} ts={} err={}",
                    row.deviceId(), row.tsMillis(), e.getMessage());
            return false;
        }
    }

    private String toPayload(Row row) {
        try {
            return objectMapper.writeValueAsString(row.params());
        } catch (Exception e) {
            throw new IllegalStateException("遥测序列化失败", e);
        }
    }

    private String subTable(String deviceId) {
        return "d_" + deviceId.replaceAll("[^A-Za-z0-9_]", "_");
    }
}

package com.github.wuzhudexiaopacai.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.telemetry.service.TelemetryIdempotencyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TelemetryBatchWriterTest {

    private JdbcTemplate jdbc;
    private TelemetryBatchWriter writer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        writer = new TelemetryBatchWriter(jdbc, new ObjectMapper());
    }

    @Test
    void write_groupsRowsIntoOneInsertPerDevice() {
        List<TelemetryBatchWriter.Row> rows = List.of(
                row("dev-1", 1L), row("dev-1", 2L), row("dev-2", 3L));

        int written = writer.write(rows);

        assertEquals(3, written);
        // dev-1 一次批量 + dev-2 单条
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void write_singleRowUsesSingleInsert() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertEquals(1, writer.write(List.of(row("dev-1", 1L))));
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void write_batchFailure_fallsBackToPerRowRetry() {
        // 第一次批量失败，后续单条成功
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("batch failed"))
                .thenReturn(1)
                .thenReturn(1);

        int written = writer.write(List.of(row("dev-1", 1L), row("dev-1", 2L)));

        assertEquals(2, written);
    }

    @Test
    void writeOne_returnsFalseOnFailure() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("td down"));

        assertFalse(writer.writeOne(row("dev-1", 1L)));
    }

    @Test
    void write_emptyList_returnsZero() {
        assertEquals(0, writer.write(List.of()));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void subTable_sanitizesUnsafeCharacters() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        writer.write(List.of(row("dev/../x", 1L)));

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("coherer.d_dev____x"), any(Object[].class));
    }

    private TelemetryBatchWriter.Row row(String deviceId, long ts) {
        return new TelemetryBatchWriter.Row("default", deviceId, 10L, 1L, ts, Map.of("t", 1));
    }
}

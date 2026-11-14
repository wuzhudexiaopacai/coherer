package com.github.wuzhudexiaopacai.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.telemetry.service.TelemetryIdempotencyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelemetryIngestBufferTest {

    private TelemetryBatchWriter writer;
    private TelemetryIdempotencyService idempotencyService;
    private SimpleMeterRegistry meterRegistry;
    private TelemetryIngestBuffer buffer;

    @BeforeEach
    void setUp() {
        writer = mock(TelemetryBatchWriter.class);
        idempotencyService = mock(TelemetryIdempotencyService.class);
        meterRegistry = new SimpleMeterRegistry();
        buffer = new TelemetryIngestBuffer(writer, idempotencyService, meterRegistry, 10, 5);
    }

    @Test
    void flush_writesBufferedRowsInOneBatch() {
        when(writer.write(anyList())).thenReturn(3);
        buffer.offer(row("dev-1"), "key-1");
        buffer.offer(row("dev-1"), "key-2");
        buffer.offer(row("dev-1"), "key-3");

        buffer.flush();

        verify(writer).write(anyList());
        assertEquals(0, buffer.pending());
    }

    @Test
    void offer_fullQueue_returnsFalseForFallback() {
        for (int i = 0; i < 10; i++) {
            assertTrue(buffer.offer(row("dev-" + i), "key-" + i));
        }

        assertTrue(!buffer.offer(row("dev-x"), "key-x"));
        assertEquals(10, buffer.pending());
    }

    @Test
    void flush_partialFailure_releasesDedupKeyForFailedRows() {
        when(writer.write(anyList())).thenReturn(1);
        when(writer.writeOne(any(TelemetryBatchWriter.Row.class))).thenReturn(true, false);
        buffer.offer(row("dev-1"), "key-1");
        buffer.offer(row("dev-1"), "key-2");

        buffer.flush();

        // 第一条成功、第二条失败 → 只释放失败行的幂等键
        verify(idempotencyService).release("key-2");
        verify(idempotencyService, never()).release("key-1");
    }

    @Test
    void flush_emptyQueue_doesNothing() {
        buffer.flush();

        verify(writer, never()).write(anyList());
    }

    @Test
    void drainOnShutdown_flushesRemainingRows() {
        when(writer.write(anyList())).thenReturn(1);
        buffer.offer(row("dev-1"), "key-1");

        buffer.drainOnShutdown();

        verify(writer).write(anyList());
        assertEquals(0, buffer.pending());
    }

    private TelemetryBatchWriter.Row row(String deviceId) {
        return new TelemetryBatchWriter.Row("default", deviceId, 10L, 1L, 1L, Map.of("t", 1));
    }
}

package com.github.wuzhudexiaopacai.telemetry;

import com.github.wuzhudexiaopacai.telemetry.service.TelemetryIdempotencyService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 遥测写入缓冲：把 MQTT 回调里的同步单条写入改为有界队列 + 定时批量落库。
 *
 * <ul>
 *   <li>队列满时立刻降级为同步单条写入（背压），不丢数据也不阻塞 MQTT 回调过久</li>
 *   <li>批量失败由 {@link TelemetryBatchWriter} 逐条降级重试，坏数据只影响自身</li>
 *   <li>失败行会释放幂等键，允许 MQTT 重投重新处理</li>
 * </ul>
 */
@Slf4j
@Component
public class TelemetryIngestBuffer {

    private final TelemetryBatchWriter writer;
    private final TelemetryIdempotencyService idempotencyService;
    private final MeterRegistry meterRegistry;
    private final BlockingQueue<BufferedRow> queue;
    private final int batchSize;

    private final AtomicLong dropped = new AtomicLong();

    public TelemetryIngestBuffer(
            TelemetryBatchWriter writer,
            TelemetryIdempotencyService idempotencyService,
            MeterRegistry meterRegistry,
            @Value("${coherer.telemetry.buffer-capacity:10000}") int capacity,
            @Value("${coherer.telemetry.batch-size:200}") int batchSize) {
        this.writer = writer;
        this.idempotencyService = idempotencyService;
        this.meterRegistry = meterRegistry;
        this.queue = new ArrayBlockingQueue<>(Math.max(capacity, 1));
        this.batchSize = Math.max(batchSize, 1);
        meterRegistry.gauge("coherer.telemetry.buffer.size", queue, BlockingQueue::size);
        meterRegistry.gauge("coherer.telemetry.buffer.capacity", queue, q -> capacity);
    }

    /** 行 + 幂等键：写入失败时需要回滚幂等键。 */
    public record BufferedRow(TelemetryBatchWriter.Row row, String dedupKey) {
    }

    /**
     * 提交一行待写入数据。
     *
     * @return true 表示已入队；false 表示队列已满，调用方应同步写入兜底
     */
    public boolean offer(TelemetryBatchWriter.Row row, String dedupKey) {
        boolean accepted = queue.offer(new BufferedRow(row, dedupKey));
        if (!accepted) {
            dropped.incrementAndGet();
            meterRegistry.counter("coherer.telemetry.buffer.overflow").increment();
            log.warn("遥测缓冲已满，降级为同步写入 deviceId={}", row.deviceId());
        }
        return accepted;
    }

    /** 定时批量落库。 */
    @Scheduled(fixedDelayString = "${coherer.telemetry.flush-interval-ms:200}")
    public void flush() {
        List<BufferedRow> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return;
        }
        List<TelemetryBatchWriter.Row> rows = batch.stream().map(BufferedRow::row).toList();
        int written = 0;
        try {
            written = writer.write(rows);
        } catch (Exception e) {
            log.warn("遥测批量落库异常，逐条重试 count={} err={}", rows.size(), e.getMessage());
        }

        if (written == rows.size()) {
            meterRegistry.counter("coherer.telemetry.batch.flushed").increment();
            meterRegistry.counter("coherer.telemetry.batch.rows").increment(rows.size());
            return;
        }

        // 批量整体失败时逐条写入，精确定位失败行并释放其幂等键
        int succeeded = 0;
        for (BufferedRow buffered : batch) {
            if (writer.writeOne(buffered.row())) {
                succeeded++;
                continue;
            }
            meterRegistry.counter("coherer.telemetry.ingested", "result", "error").increment();
            if (buffered.dedupKey() != null) {
                idempotencyService.release(buffered.dedupKey());
            }
        }
        meterRegistry.counter("coherer.telemetry.batch.flushed").increment();
        meterRegistry.counter("coherer.telemetry.batch.rows").increment(succeeded);
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int pending() {
        return queue.size();
    }

    /** 停机前把队列剩余数据写完，避免正常发布会丢最后一批遥测。 */
    @jakarta.annotation.PreDestroy
    public void drainOnShutdown() {
        int remaining = queue.size();
        if (remaining == 0) {
            return;
        }
        log.info("停机前冲刷遥测缓冲 pending={}", remaining);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 1000) {
            flush();
        }
    }
}

package com.github.wuzhudexiaopacai.ota.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 灰度批次推进器：独立 Bean，确保事务边界经过 Spring 代理。 */
@Component
@RequiredArgsConstructor
public class OtaRolloutScheduler {

    private static final Logger log = LoggerFactory.getLogger(OtaRolloutScheduler.class);

    private final OtaRolloutService rolloutService;

    @Scheduled(fixedDelayString = "${coherer.ota.rollout-sweep-interval-ms:5000}",
            initialDelayString = "${coherer.ota.rollout-sweep-initial-delay-ms:10000}")
    public void advanceRollouts() {
        com.github.wuzhudexiaopacai.security.TenantContext.runBypass(() -> {
            for (Long id : rolloutService.activeRolloutIds()) {
                try {
                    rolloutService.advance(id);
                } catch (Exception e) {
                    // 单个批次异常不能阻塞其他批次
                    log.warn("OTA 灰度推进失败 rolloutId={} err={}", id, e.getMessage());
                }
            }
        });
    }
}

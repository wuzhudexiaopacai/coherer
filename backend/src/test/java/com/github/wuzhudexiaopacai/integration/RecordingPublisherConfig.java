package com.github.wuzhudexiaopacai.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class RecordingPublisherConfig {

    @Bean
    RecordingPublisher recordingPublisher() {
        return new RecordingPublisher();
    }
}

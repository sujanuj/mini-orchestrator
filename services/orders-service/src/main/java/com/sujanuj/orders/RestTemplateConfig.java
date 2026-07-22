package com.sujanuj.orders;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    /**
     * A short, explicit timeout matters here specifically because this
     * service exists to demonstrate resilience under the orchestrator
     * (Phase 2+): when inventory-service is killed to test auto-restart,
     * requests from orders-service to it should fail FAST with a clear
     * 503, not hang for Java's default (very long) connection timeout
     * while the orchestrator is in the middle of restarting the
     * dependency.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }
}
package com.metricol.api.config;

import java.time.LocalDateTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "app.creative-director")
@Getter
@Setter
public class CreativeDirectorProperties {
    private boolean enabled = false;
    private int workers = 1;
    private int batchSize = 5;
    private int maxAttempts = 3;
    private int timeoutSeconds = 75;
    private long staleAfterSeconds = 180;
    private long retryBaseSeconds = 15;
    private long retryMaxSeconds = 120;
    private String recipeVersion = "creative-plan-v1";

    public LocalDateTime nextAttempt(int attempt) {
        long delay = retryBaseSeconds;
        for (int i = 1; i < Math.max(1, attempt); i++) {
            delay = Math.min(retryMaxSeconds, delay * 2);
        }
        return LocalDateTime.now().plusSeconds(delay);
    }
}

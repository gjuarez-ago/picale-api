package com.metricol.api.config;

import java.time.LocalDateTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/** Límites operativos y receta inicial del generador de reels. */
@Configuration
@ConfigurationProperties(prefix = "app.reels")
@Getter
@Setter
public class ReelsProperties {
    private boolean enabled = false;
    private int workers = 1;
    private int minPhotos = 4;
    private int maxPhotos = 8;
    private int minDurationSeconds = 15;
    private int maxDurationSeconds = 30;
    private int defaultDurationSeconds = 20;
    private int timeoutSeconds = 240;
    private int maxAttempts = 3;
    private long retryBaseSeconds = 30;
    private long retryMaxSeconds = 300;
    private long staleAfterSeconds = 600;
    private int batchSize = 5;
    private String ffmpeg = "ffmpeg";
    private String ffprobe = "ffprobe";
    private String recipeVersion = "photos-v1";

    public LocalDateTime nextAttempt(int attempt) {
        long delay = retryBaseSeconds;
        for (int i = 1; i < Math.max(1, attempt); i++) {
            delay = Math.min(retryMaxSeconds, delay * 2);
        }
        return LocalDateTime.now().plusSeconds(delay);
    }
}

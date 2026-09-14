package com.metricol.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Credenciales de upload-post.com (https://docs.upload-post.com) — el
 * servicio que hace la publicación real en cada red social por nosotros.
 */
@Configuration
@ConfigurationProperties(prefix = "uploadpost")
@Getter
@Setter
public class UploadPostProperties {

    private String apiKey;

    private String baseUrl;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}

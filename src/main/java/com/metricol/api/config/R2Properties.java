package com.metricol.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Credenciales y ubicación del bucket de Cloudflare R2 donde se guardan las
 * fotos y videos de las publicaciones. Mismo patrón que R2Properties de
 * vivento366.api — un valor por variable de entorno, sin default en
 * producción para las credenciales.
 */
@Configuration
@ConfigurationProperties(prefix = "cloudflare.r2")
@Getter
@Setter
public class R2Properties {

    /** Ej. https://<account-id>.r2.cloudflarestorage.com */
    private String endpoint;

    private String accessKey;

    private String secretKey;

    private String bucket;

    /** URL pública del bucket (dominio propio o el *.r2.dev que da Cloudflare). */
    private String publicUrl;

    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && bucket != null && !bucket.isBlank()
                && publicUrl != null && !publicUrl.isBlank();
    }
}

package com.metricol.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/** Worker local opcional. Sin directorio de modelos no está disponible para ejecutar. */
@Configuration
@ConfigurationProperties(prefix = "app.video.speaker-analysis")
@Validated
@Getter
@Setter
public class AnalisisHablanteProperties {
    @NotBlank
    private String python = "python3";
    @NotBlank
    private String worker = "tools/video_hablante/analizar.py";
    private String modelsDirectory = "";
    @Min(1) @Max(1800)
    private int timeoutSeconds = 300;
    @Min(1) @Max(536870912)
    private long maxSourceBytes = 512L * 1024 * 1024;
}

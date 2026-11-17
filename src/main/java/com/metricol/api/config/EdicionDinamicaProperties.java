package com.metricol.api.config;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/** Control del piloto. Encenderlo con una lista vacía no habilita ninguna cuenta. */
@Configuration
@ConfigurationProperties(prefix = "app.video.dynamic-editing")
@Validated
@Getter
@Setter
public class EdicionDinamicaProperties {
    private boolean enabled = false;

    @NotNull
    @Size(max = 100)
    private Set<UUID> pilotWorkspaces = Set.of();
}

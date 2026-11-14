package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MediaMobileReadyConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(Configuracion.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MediaLimitsProperties.class)
    static class Configuracion {
    }

    @Test
    void permaneceApagadoDeFabrica() {
        contexto.run(c -> assertThat(c.getBean(MediaLimitsProperties.class)
                .getMultipart().isMobileReady()).isFalse());
    }

    @Test
    void sePuedeEncenderDesdeConfiguracionExterna() {
        contexto.withPropertyValues("app.media.multipart.mobile-ready=true")
                .run(c -> assertThat(c.getBean(MediaLimitsProperties.class)
                        .getMultipart().isMobileReady()).isTrue());
    }
}

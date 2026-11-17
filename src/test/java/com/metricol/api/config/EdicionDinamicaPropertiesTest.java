package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EdicionDinamicaPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(EdicionDinamicaProperties.class);

    @Test
    void listaVaciaDePropertiesNoHabilitaCuentas() {
        runner.withPropertyValues("app.video.dynamic-editing.pilot-workspaces=").run(context -> {
            assertThat(context).hasNotFailed();
            var config = context.getBean(EdicionDinamicaProperties.class);
            assertThat(config.isEnabled()).isFalse();
            assertThat(config.getPilotWorkspaces()).isEmpty();
        });
    }

    @Test
    void aceptaListaDeCuentasPeroFallaAlArrancarConUnIdentificadorInvalido() {
        runner.withPropertyValues("app.video.dynamic-editing.enabled=true",
                "app.video.dynamic-editing.pilot-workspaces=10000000-0000-0000-0000-000000000001")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EdicionDinamicaProperties.class).isEnabled()).isTrue();
                    assertThat(context.getBean(EdicionDinamicaProperties.class).getPilotWorkspaces()).hasSize(1);
                });
        runner.withPropertyValues("app.video.dynamic-editing.pilot-workspaces=cuenta-inventada")
                .run(context -> assertThat(context).hasFailed());
    }
}

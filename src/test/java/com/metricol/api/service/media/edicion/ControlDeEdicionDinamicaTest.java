package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.service.media.edicion.ControlDeEdicionDinamica.Decision;

class ControlDeEdicionDinamicaTest {
    @Test
    void apagadaPorDefectoInclusoParaUnaCuentaEnLaLista() {
        var config = new EdicionDinamicaProperties();
        config.setPilotWorkspaces(Set.of(DatosDeEdicion.WS));
        assertThat(new ControlDeEdicionDinamica(config).evaluar(DatosDeEdicion.WS,
                PerfilEdicionDinamica.SUBTLE, false)).isEqualTo(Decision.DISABLED);
    }

    @Test
    void encendidaSinListaNoAbreElPilotoATodasLasCuentas() {
        var config = new EdicionDinamicaProperties();
        config.setEnabled(true);
        var control = new ControlDeEdicionDinamica(config);
        assertThat(control.evaluar(DatosDeEdicion.WS, PerfilEdicionDinamica.SUBTLE, false))
                .isEqualTo(Decision.WORKSPACE_NOT_ENABLED);
        config.setPilotWorkspaces(Set.of(DatosDeEdicion.WS));
        assertThat(control.evaluar(UUID.randomUUID(), PerfilEdicionDinamica.SUBTLE, false))
                .isEqualTo(Decision.WORKSPACE_NOT_ENABLED);
    }

    @Test
    void conservaPreferenciaApagadaYExcluyeVideosYaEditados() {
        var config = new EdicionDinamicaProperties();
        config.setEnabled(true);
        config.setPilotWorkspaces(Set.of(DatosDeEdicion.WS));
        var control = new ControlDeEdicionDinamica(config);
        assertThat(control.evaluar(DatosDeEdicion.WS, PerfilEdicionDinamica.OFF, false))
                .isEqualTo(Decision.PROFILE_OFF);
        assertThat(control.evaluar(DatosDeEdicion.WS, null, false)).isEqualTo(Decision.PROFILE_OFF);
        assertThat(control.evaluar(DatosDeEdicion.WS, PerfilEdicionDinamica.SUBTLE, true))
                .isEqualTo(Decision.ALREADY_EDITED);
        assertThat(control.evaluar(DatosDeEdicion.WS, PerfilEdicionDinamica.SUBTLE, false))
                .isEqualTo(Decision.ANALYSIS_ALLOWED);
    }
}

package com.metricol.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.ObjetivoRedes;

/** El perfil obligatorio del negocio: nombre, giro, descripción y objetivo. La ciudad no cuenta. */
class WorkspacePerfilCompletoTest {

    private static Workspace completo() {
        return Workspace.builder().name("Tacos").giro("Restaurante").descripcion("Taquería")
                .objetivo(ObjetivoRedes.MAS_CLIENTES).build();
    }

    @Test
    @DisplayName("con nombre, giro, descripción y objetivo está completo, aunque falte la ciudad")
    void completoSinCiudad() {
        assertThat(completo().perfilCompleto()).isTrue();
    }

    @Test
    @DisplayName("si falta cualquiera de los cuatro, no")
    void faltaUno() {
        Workspace a = completo(); a.setName(" ");
        Workspace b = completo(); b.setGiro(null);
        Workspace c = completo(); c.setDescripcion("");
        Workspace d = completo(); d.setObjetivo(null);

        assertThat(a.perfilCompleto()).isFalse();
        assertThat(b.perfilCompleto()).isFalse();
        assertThat(c.perfilCompleto()).isFalse();
        assertThat(d.perfilCompleto()).isFalse();
        assertThat(Workspace.builder().name("Solo nombre").build().perfilCompleto()).isFalse();
    }
}

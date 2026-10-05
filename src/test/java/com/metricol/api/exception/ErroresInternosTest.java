package com.metricol.api.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.HashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.metricol.api.enums.Permission;

/** Lo que falla por dentro no se le enseña en inglés a quien usa la app. */
class ErroresInternosTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("un error de Java (Collection is empty) da un mensaje claro y no el original")
    void deJava() {
        IllegalArgumentException ex = null;
        try {
            EnumSet.copyOf(new HashSet<Permission>());
        } catch (IllegalArgumentException e) {
            ex = e;
        }
        assertThat(GlobalExceptionHandler.esNuestro(ex)).isFalse();
        var r = handler.handleIllegalArgument(ex, new MockHttpServletRequest("POST", "/api/v1/team/invitaciones"));
        assertThat(r.getBody().getUserMessage()).doesNotContain("Collection").contains("error de nuestro lado");
    }

    @Test
    @DisplayName("uno nuestro, con su mensaje en español, se muestra tal cual")
    void nuestro() {
        IllegalArgumentException ex = new IllegalArgumentException("Elige al menos un día para publicar.");
        assertThat(GlobalExceptionHandler.esNuestro(ex)).isTrue();
        var r = handler.handleIllegalArgument(ex, new MockHttpServletRequest("PUT", "/api/v1/agente/horario"));
        assertThat(r.getBody().getUserMessage()).isEqualTo("Elige al menos un día para publicar.");
    }
}

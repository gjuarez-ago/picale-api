package com.metricol.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.PostSaveRequest;

class PostControllerPermisosTest {

    private PostSaveRequest peticion(boolean ahora, LocalDateTime cuando) {
        PostSaveRequest request = new PostSaveRequest();
        request.setCaption("Hola");
        request.setPublishNow(ahora);
        request.setScheduledAt(cuando);
        return request;
    }

    @Test
    @DisplayName("un borrador solo pide crear: no sale a ninguna red")
    void borradorSoloPideCrear() {
        assertThat(PostController.permisosDeGuardado(peticion(false, null)))
                .containsExactly(Permission.POST_CREATE);
    }

    @Test
    @DisplayName("salir ya pide publicar")
    void salirYaPidePublicar() {
        assertThat(PostController.permisosDeGuardado(peticion(true, null)))
                .containsExactly(Permission.POST_CREATE, Permission.POST_PUBLISH);
    }

    @Test
    @DisplayName("programar pide programar")
    void programarPideProgramar() {
        assertThat(PostController.permisosDeGuardado(peticion(false, LocalDateTime.now().plusDays(1))))
                .containsExactly(Permission.POST_CREATE, Permission.POST_SCHEDULE);
    }
}

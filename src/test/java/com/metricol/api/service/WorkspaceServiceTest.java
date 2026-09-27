package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.models.request.WorkspaceUpdateRequest;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * {@code PUT /workspace} solo toca lo que viene.
 *
 * <p>El caso que lo trajo: completar el negocio sin elegir foto nueva dejaba
 * al espacio sin logotipo, porque el campo que no venía se guardaba como nulo.
 */
class WorkspaceServiceTest {

    private WorkspaceRepository repository;
    private WorkspaceService service;
    private Workspace workspace;
    private User user;

    @BeforeEach
    void preparar() {
        repository = mock(WorkspaceRepository.class);
        service = new WorkspaceService(repository);
        workspace = Workspace.builder()
                .id(UUID.randomUUID())
                .name("Tacos")
                .logoUrl("https://cdn.test/logos/a/logo.png")
                .uploadPostProfile("perfil-a-mano")
                .build();
        user = User.builder().workspace(workspace).build();
        when(repository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        when(repository.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("Sin logoUrl ni uploadPostProfile en la petición, se conservan")
    void conservaLoQueNoViene() {
        WorkspaceUpdateRequest pedido = new WorkspaceUpdateRequest();
        pedido.setName("Tacos Don Pepe");
        pedido.setGiro("Restaurante");

        service.update(user, pedido);

        assertThat(workspace.getName()).isEqualTo("Tacos Don Pepe");
        assertThat(workspace.getLogoUrl()).isEqualTo("https://cdn.test/logos/a/logo.png");
        assertThat(workspace.getUploadPostProfile()).isEqualTo("perfil-a-mano");
    }

    @Test
    @DisplayName("Vacío sí borra el logotipo; una URL nueva lo cambia")
    void vacioBorraYNuevoCambia() {
        WorkspaceUpdateRequest borrar = new WorkspaceUpdateRequest();
        borrar.setName("Tacos");
        borrar.setLogoUrl("");
        service.update(user, borrar);
        assertThat(workspace.getLogoUrl()).isNull();

        WorkspaceUpdateRequest cambiar = new WorkspaceUpdateRequest();
        cambiar.setName("Tacos");
        cambiar.setLogoUrl("https://cdn.test/logos/a/nuevo.png");
        service.update(user, cambiar);
        assertThat(workspace.getLogoUrl()).isEqualTo("https://cdn.test/logos/a/nuevo.png");
    }
}

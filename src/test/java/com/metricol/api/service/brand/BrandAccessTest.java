package com.metricol.api.service.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.metricol.api.entity.BrandProfile;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.ObjetivoRedes;
import com.metricol.api.exception.MarcaIncompletaException;
import com.metricol.api.repository.WorkspaceRepository;

class BrandAccessTest {

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final BrandAccess access = new BrandAccess(workspaces);

    @Test
    void rechazaConPorcentajeYFaltantes() {
        Workspace w = Workspace.builder().id(UUID.randomUUID()).name("Negocio").giro("Café").build();
        User user = User.builder().workspace(w).build();
        when(workspaces.findById(w.getId())).thenReturn(Optional.of(w));

        assertThatThrownBy(() -> access.exigirCompleta(user))
                .isInstanceOfSatisfying(MarcaIncompletaException.class, ex -> {
                    assertThat(ex.getPercent()).isLessThan(100);
                    assertThat(ex.getMissing()).contains("ciudad", "descripcion", "contacto");
                });
    }

    @Test
    void aceptaSoloLosOchoDatosCompletos() {
        Workspace w = Workspace.builder()
                .id(UUID.randomUUID()).name("Negocio").giro("Café").ciudad("Mérida")
                .descripcion("Café de especialidad").objetivo(ObjetivoRedes.MAS_CLIENTES)
                .brandProfile(new BrandProfile("Café y pan", "Personas de la zona", List.of("CERCANO"),
                        null, "+529991234567", null, null))
                .build();
        User user = User.builder().workspace(w).build();
        when(workspaces.findById(w.getId())).thenReturn(Optional.of(w));

        assertThat(access.estado(user).percent()).isEqualTo(100);
        access.exigirCompleta(user);
    }
}

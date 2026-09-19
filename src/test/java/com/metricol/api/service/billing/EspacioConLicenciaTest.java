package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Organization;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.ObjetivoRedes;
import com.metricol.api.enums.Role;
import com.metricol.api.models.request.WorkspaceCreateRequest;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.WorkspaceMembershipService;

/**
 * Crear espacios con los cobros encendidos, contra la base de verdad. Se revierte
 * al terminar; la configuración va simulada para no tocar el flag real.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.billing.sweep-initial-delay-ms=3600000"
})
class EspacioConLicenciaTest {

    @Autowired
    private WorkspaceMembershipService membresias;
    @Autowired
    private OrganizationRepository organizaciones;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private UserRepository users;
    @Autowired
    private WorkspaceMemberRepository miembros;

    @MockitoBean
    private BillingConfig config;

    private Organization org;
    private User comprador;

    @BeforeEach
    void preparar() {
        org = organizaciones.save(Organization.builder().name("Agencia de prueba").maxWorkspaces(1).build());
        Workspace primero = workspaces.save(Workspace.builder().name("Primero").organization(org).build());
        comprador = users.save(User.builder().name("Dueño").email("dueno-" + UUID.randomUUID() + "@test.local")
                .password("no-se-usa").role(Role.ADMIN).workspace(primero).build());
    }

    private static WorkspaceCreateRequest datos(String nombre) {
        WorkspaceCreateRequest d = new WorkspaceCreateRequest();
        d.setName(nombre);
        d.setGiro("  Restaurante ");
        d.setCiudad("Mérida, Yucatán");
        d.setDescripcion("   ");
        d.setObjetivo(ObjetivoRedes.MAS_CLIENTES);
        return d;
    }

    @Test
    @DisplayName("con los cobros encendidos un espacio no se crea a secas: se compra")
    void noSeCreaSinPagar() {
        when(config.habilitado()).thenReturn(true);

        assertThatThrownBy(() -> membresias.crear(comprador, "Sin pagar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("licencia");
    }

    @Test
    @DisplayName("con los cobros apagados se crea como siempre")
    void apagadoCreaComoSiempre() {
        when(config.habilitado()).thenReturn(false);
        // El tope de 1 espacio está lleno: aquí solo se comprueba que NO llegue el bloqueo de cobros.
        assertThatThrownBy(() -> membresias.crear(comprador, "Otro"))
                .hasMessageNotContaining("licencia");
    }

    @Test
    @DisplayName("un espacio de una licencia pagada nace con los datos del negocio, aunque el tope de espacios esté lleno")
    void naceConLicencia() {
        Workspace nuevo = membresias.crearParaLicencia(org.getId(), comprador.getId(), datos("  Tacos El Güero "));

        Workspace guardado = workspaces.findById(nuevo.getId()).orElseThrow();
        assertThat(guardado.getName()).isEqualTo("Tacos El Güero");
        assertThat(guardado.getGiro()).isEqualTo("Restaurante");
        assertThat(guardado.getCiudad()).isEqualTo("Mérida, Yucatán");
        assertThat(guardado.getDescripcion()).as("un texto en blanco no es un dato").isNull();
        assertThat(guardado.getObjetivo()).isEqualTo(ObjetivoRedes.MAS_CLIENTES);
        assertThat(guardado.getOrganization().getId()).isEqualTo(org.getId());
        // Quien compró queda como ADMIN del espacio nuevo.
        assertThat(miembros.existsByUserIdAndWorkspaceId(comprador.getId(), nuevo.getId())).isTrue();
        // Y no queda archivado ni bloqueado.
        assertThat(guardado.archivado()).isFalse();
    }
}

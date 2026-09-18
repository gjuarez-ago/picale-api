package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.models.response.MiWorkspaceResponse;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Un usuario con varios clientes, cada uno en su workspace.
 *
 * <p>La prueba que importa es la del workspace ajeno: es la única barrera
 * entre los datos de dos clientes, y un fallo ahí no se ve en ninguna pantalla
 * — alguien simplemente entraría a las publicaciones de otro.
 *
 * <p>Borra lo que crea al terminar: la base del perfil dev puede ser un
 * Postgres que persiste, y sin limpiar cada corrida dejaría usuarios y
 * workspaces de prueba mezclados con los de verdad.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000"
})
class WorkspaceMembershipServiceTest {

    @Autowired
    private WorkspaceMembershipService membresias;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceMemberRepository miembros;

    @Autowired
    private OrganizationMemberRepository orgMiembros;

    @Autowired
    private OrganizationRepository organizaciones;

    private final List<UUID> usuariosCreados = new ArrayList<>();
    private final List<UUID> workspacesCreados = new ArrayList<>();

    /**
     * En orden inverso a las llaves: primero lo que apunta a un usuario o a un
     * workspace, luego ellos. Las organizaciones van al final porque los
     * workspaces las referencian.
     */
    @AfterEach
    void limpiar() {
        List<UUID> organizacionesCreadas = new ArrayList<>();
        for (UUID userId : usuariosCreados) {
            miembros.deleteAllById(miembros.findDelUsuario(userId).stream().map(WorkspaceMember::getId).toList());
            orgMiembros.findDelUsuario(userId).forEach(m -> {
                organizacionesCreadas.add(m.getOrganization().getId());
                orgMiembros.deleteById(m.getId());
            });
        }
        users.deleteAllById(usuariosCreados);
        workspaces.deleteAllById(workspacesCreados);
        organizaciones.deleteAllById(organizacionesCreadas);
        usuariosCreados.clear();
        workspacesCreados.clear();
    }

    private Workspace workspace(String nombre) {
        Workspace creado = workspaces.save(Workspace.builder().name(nombre).build());
        workspacesCreados.add(creado.getId());
        return creado;
    }

    private User usuario(Workspace activo) {
        User creado = users.save(User.builder()
                .name("Agencia")
                .email("agencia-" + UUID.randomUUID() + "@test.local")
                .password("no-se-usa")
                .role(Role.ADMIN)
                .workspace(activo)
                .build());
        usuariosCreados.add(creado.getId());
        return creado;
    }

    private void darAcceso(User user, Workspace workspace) {
        miembros.save(WorkspaceMember.de(user, workspace, Role.ADMIN));
    }

    private UUID activoDe(User user) {
        return users.findById(user.getId()).orElseThrow().getWorkspace().getId();
    }

    @Test
    @DisplayName("lista todos sus workspaces y marca el activo")
    void listaSusWorkspaces() {
        Workspace tacos = workspace("Tacos Don Pepe");
        Workspace gym = workspace("Gym Fuerza");
        User agencia = usuario(tacos);
        darAcceso(agencia, tacos);
        darAcceso(agencia, gym);

        List<MiWorkspaceResponse> mios = membresias.misWorkspaces(agencia);

        assertThat(mios).extracting(MiWorkspaceResponse::name)
                .containsExactlyInAnyOrder("Tacos Don Pepe", "Gym Fuerza");
        assertThat(mios).filteredOn(MiWorkspaceResponse::activo)
                .extracting(MiWorkspaceResponse::id)
                .containsExactly(tacos.getId());
    }

    @Test
    @DisplayName("activar uno propio lo deja activo y devuelve la sesion de ese workspace")
    void activaUnoPropio() {
        Workspace tacos = workspace("Tacos");
        Workspace gym = workspace("Gym");
        User agencia = usuario(tacos);
        darAcceso(agencia, tacos);
        darAcceso(agencia, gym);

        AuthResponse sesion = membresias.activar(agencia, gym.getId());

        assertThat(sesion.getWorkspaceId()).isEqualTo(gym.getId());
        assertThat(sesion.getWorkspaceName()).isEqualTo("Gym");
        assertThat(sesion.getToken()).isNotBlank();
        assertThat(activoDe(agencia)).isEqualTo(gym.getId());
    }

    @Test
    @DisplayName("no se puede activar un workspace ajeno, aunque se conozca su id")
    void noActivaUnoAjeno() {
        Workspace mio = workspace("Mio");
        Workspace ajeno = workspace("De otro cliente");
        User yo = usuario(mio);
        darAcceso(yo, mio);

        assertThatThrownBy(() -> membresias.activar(yo, ajeno.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(activoDe(yo)).isEqualTo(mio.getId());
    }

    @Test
    @DisplayName("crear uno lo suma a los suyos sin cambiarle el activo")
    void creaSinCambiarElActivo() {
        Workspace mio = workspace("Agencia");
        User yo = usuario(mio);
        darAcceso(yo, mio);

        MiWorkspaceResponse nuevo = membresias.crear(yo, "  Cafeteria La Esquina  ");
        workspacesCreados.add(nuevo.id());

        assertThat(nuevo.name()).isEqualTo("Cafeteria La Esquina");
        assertThat(nuevo.activo()).isFalse();
        assertThat(miembros.existsByUserIdAndWorkspaceId(yo.getId(), nuevo.id())).isTrue();
        assertThat(activoDe(yo)).isEqualTo(mio.getId());
    }

    @Test
    @DisplayName("a quien existia antes de las membresias se le crea la de su workspace, una sola vez")
    void completaLasFaltantes() {
        Workspace deAntes = workspace("De antes");
        User antiguo = usuario(deAntes);
        assertThat(miembros.existsByUserIdAndWorkspaceId(antiguo.getId(), deAntes.getId())).isFalse();

        membresias.completarMembresiasFaltantes();
        membresias.completarMembresiasFaltantes();

        assertThat(miembros.findDelUsuario(antiguo.getId())).hasSize(1);
        assertThat(membresias.misWorkspaces(antiguo))
                .extracting(MiWorkspaceResponse::id)
                .containsExactly(deAntes.getId());
    }
}

package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Role;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.MiWorkspaceResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.storage.R2StorageService;

/**
 * El logotipo de un espacio queda a nombre de ESE espacio, no del que esté
 * activo en la sesión de quien lo sube.
 *
 * <p>Es el error que se reportó el 18 sep 2026: con "Taquería A" activo, se
 * subió el logotipo de "Gimnasio B" desde el modal de administrar espacios, y
 * el archivo apareció en el Contenido de "Taquería A". Un mock no lo habría
 * atrapado —el fallo está en cuándo Hibernate resuelve el tenant respecto a
 * la transacción—, por eso corre con el contexto de Spring completo.
 *
 * <p>R2 sí se sustituye: no se sube nada real a Cloudflare desde una prueba.
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
class WorkspaceLogoUploadTest {

    @Autowired
    private WorkspaceMembershipService membresias;

    @Autowired
    private MediaAssetRepository archivos;

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

    @MockBean
    private R2StorageService storage;

    private final List<UUID> usuariosCreados = new ArrayList<>();
    private final List<UUID> workspacesCreados = new ArrayList<>();
    private final List<UUID> archivosCreados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();

        // Cada archivo se borra desde SU tenant: sin él Hibernate no lo encuentra.
        for (UUID workspaceId : workspacesCreados) {
            TenantIdentifierResolver.comoTenant(workspaceId.toString(),
                    () -> archivosCreados.forEach(id -> archivos.findById(id).ifPresent(archivos::delete)));
        }

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
        archivosCreados.clear();
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
        miembros.save(WorkspaceMember.de(creado, activo, Role.ADMIN));
        return creado;
    }

    private void comoSesionDe(User user) {
        // Lo que hace el filtro de seguridad en una petición real: de aquí sale
        // el tenant que Hibernate resuelve, y es el del espacio ACTIVO.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private Optional<MediaAsset> archivoVistoDesde(UUID tenant, UUID archivoId) {
        Object[] hallado = new Object[1];
        TenantIdentifierResolver.comoTenant(tenant.toString(), () -> hallado[0] = archivos.findById(archivoId));
        @SuppressWarnings("unchecked")
        Optional<MediaAsset> resultado = (Optional<MediaAsset>) hallado[0];
        return resultado;
    }

    @Test
    @DisplayName("el logotipo queda a nombre del espacio editado, no del activo en la sesión")
    void quedaEnElEspacioCorrecto() {
        Workspace taqueria = workspace("Taqueria A");
        User agencia = usuario(taqueria);

        // Un segundo espacio de la MISMA organización; sigue activo el primero.
        MiWorkspaceResponse gimnasio = membresias.crear(agencia, "Gimnasio B");
        workspacesCreados.add(gimnasio.id());

        when(storage.upload(any(), any())).thenReturn(new R2StorageService.UploadedFile(
                "logo.png", "media/" + gimnasio.id() + "/logo.png",
                "https://cdn.test/media/" + gimnasio.id() + "/logo.png",
                MediaType.IMAGE, 4, "image/png"));

        comoSesionDe(agencia);
        MediaAssetResponse subido = membresias.subirLogo(agencia, gimnasio.id(),
                new MockMultipartFile("file", "logo.png", "image/png", new byte[] { 1, 2, 3, 4 }));
        archivosCreados.add(subido.getId());

        assertThat(archivoVistoDesde(gimnasio.id(), subido.getId()))
                .as("el archivo tiene que verse desde el espacio al que pertenece el logotipo")
                .isPresent();
        assertThat(archivoVistoDesde(taqueria.getId(), subido.getId()))
                .as("y NO desde el espacio activo de quien lo subió: ese era el error")
                .isEmpty();
    }

    @Test
    @DisplayName("quien no administra la organización no puede subir el logotipo de un espacio")
    void sinPermisoNoSube() {
        Workspace taqueria = workspace("Taqueria A");
        User agencia = usuario(taqueria);
        MiWorkspaceResponse gimnasio = membresias.crear(agencia, "Gimnasio B");
        workspacesCreados.add(gimnasio.id());

        // Alguien de fuera de esa organización.
        Workspace ajeno = workspace("Otra agencia");
        User intruso = usuario(ajeno);

        comoSesionDe(intruso);
        assertThatThrownBy(() -> membresias.subirLogo(intruso, gimnasio.id(),
                new MockMultipartFile("file", "logo.png", "image/png", new byte[] { 1 })))
                .isInstanceOf(RuntimeException.class);
    }
}

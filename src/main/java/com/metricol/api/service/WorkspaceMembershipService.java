package com.metricol.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.models.response.MiWorkspaceResponse;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Un usuario con varios workspaces: un cliente en cada uno.
 *
 * <p><b>Cómo encaja con lo que ya existía.</b> El tenant de cada petición sale
 * de {@link User#getWorkspace()}, y todo —publicaciones, redes, contenido,
 * cuotas, el perfil de upload-post, el gasto de IA— ya estaba separado por
 * workspace. Así que cambiar de cliente es solo cambiar ese campo, después de
 * comprobar que la persona tiene acceso. Nada de lo demás se entera.
 *
 * <p>El perfil de upload-post tampoco necesita nada: cada workspace nuevo nace
 * sin perfil, y al conectar su primera red se le crea el suyo con el id del
 * workspace (ver {@code UploadPostConnectService.profileOf}). Un cliente nunca
 * ve las redes de otro.
 *
 * <p><b>Límite conocido: el activo es por usuario, no por dispositivo.</b> Si
 * alguien cambia de cliente en el teléfono, la web abierta en otro lado pasa
 * también a ese cliente en su siguiente petición. Para que cada sesión lleve el
 * suyo habría que resolver el tenant desde el claim {@code workspaceId} del
 * token —comprobando la membresía en cada petición— en vez de desde la base.
 * Se deja así a propósito mientras nadie use dos clientes a la vez.
 */
@Service
public class WorkspaceMembershipService {

    private final WorkspaceMemberRepository miembros;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final AuthService auth;

    public WorkspaceMembershipService(WorkspaceMemberRepository miembros, WorkspaceRepository workspaces,
            UserRepository users, AuthService auth) {
        this.miembros = miembros;
        this.workspaces = workspaces;
        this.users = users;
        this.auth = auth;
    }

    /** Los workspaces a los que tiene acceso, con el activo marcado. */
    @Transactional(readOnly = true)
    public List<MiWorkspaceResponse> misWorkspaces(User actual) {
        UUID activo = actual.getWorkspace().getId();
        List<WorkspaceMember> propias = miembros.findDelUsuario(actual.getId());

        if (propias.isEmpty()) {
            // Solo pasa si el arranque todavía no completó las membresías de
            // quien existía antes de ellas. Su workspace es suyo igual.
            Workspace suyo = workspaces.findById(activo)
                    .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado."));
            return List.of(respuesta(suyo, rolDe(actual), true));
        }

        return propias.stream()
                .map(m -> respuesta(m.getWorkspace(), m.getRole(), m.getWorkspace().getId().equals(activo)))
                .toList();
    }

    /**
     * Crea un workspace y le da acceso a quien lo pide.
     *
     * <p>No lo activa: dar de alta un cliente y seguir trabajando en el actual
     * es tan normal como pasar a él, y eso lo decide quien llama con
     * {@link #activar}.
     */
    @Transactional
    public MiWorkspaceResponse crear(User actual, String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El workspace necesita un nombre.");
        }
        User user = users.findById(actual.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado."));

        Workspace nuevo = workspaces.save(Workspace.builder().name(nombre.trim()).build());
        miembros.save(WorkspaceMember.de(user, nuevo, rolDe(user)));

        return respuesta(nuevo, rolDe(user), false);
    }

    /**
     * Pasa a trabajar en otro workspace y devuelve una sesión nueva de ese.
     *
     * <p>Un workspace sin membresía da 404 y no 403, igual que uno que no
     * existe: responder distinto le diría a cualquiera qué ids son de verdad.
     */
    @Transactional
    public AuthResponse activar(User actual, UUID workspaceId) {
        if (workspaceId == null || !miembros.existsByUserIdAndWorkspaceId(actual.getId(), workspaceId)) {
            throw new ResourceNotFoundException("Workspace no encontrado.");
        }

        User user = users.findById(actual.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado."));
        Workspace destino = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado."));

        user.setWorkspace(destino);
        users.save(user);

        return auth.sesionPara(user);
    }

    /**
     * Da a cada usuario la membresía de su workspace activo si no la tiene.
     * Devuelve cuántas creó; correrlo dos veces no duplica nada.
     */
    @Transactional
    public int completarMembresiasFaltantes() {
        List<User> sinMembresia = users.findSinMembresiaEnSuWorkspace();
        for (User user : sinMembresia) {
            miembros.save(WorkspaceMember.de(user, user.getWorkspace(), rolDe(user)));
        }
        return sinMembresia.size();
    }

    private static MiWorkspaceResponse respuesta(Workspace workspace, Role role, boolean activo) {
        return new MiWorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getLogoUrl(),
                role, activo);
    }

    private static Role rolDe(User user) {
        return user.getRole() == null ? Role.ADMIN : user.getRole();
    }
}

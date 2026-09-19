package com.metricol.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.multipart.MultipartFile;

import com.metricol.api.entity.Organization;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.OrgPermission;
import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.EspacioUpdateRequest;
import com.metricol.api.models.request.WorkspaceCreateRequest;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.models.response.MediaAssetResponse;
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
    private final OrganizationService organizaciones;
    private final WorkspaceLogoUploader logoUploader;

    public WorkspaceMembershipService(WorkspaceMemberRepository miembros, WorkspaceRepository workspaces,
            UserRepository users, AuthService auth,
            OrganizationService organizaciones, WorkspaceLogoUploader logoUploader) {
        this.miembros = miembros;
        this.workspaces = workspaces;
        this.users = users;
        this.auth = auth;
        this.organizaciones = organizaciones;
        this.logoUploader = logoUploader;
    }

    /**
     * Los espacios a los que puede entrar, con el activo marcado.
     *
     * <p>Dos listas distintas según quién pregunte, y es la diferencia que
     * justifica la capa de organización: quien la administra ve TODOS sus
     * clientes sin estar apuntado en ninguno; los demás, solo los que se les
     * asignaron. Un subordinado no debe enterarse siquiera de que existen los
     * otros veintisiete.
     */
    @Transactional(readOnly = true)
    public List<MiWorkspaceResponse> misWorkspaces(User actual) {
        UUID activo = actual.getWorkspace().getId();

        Organization organizacion = workspaces.findById(activo)
                .map(Workspace::getOrganization)
                .orElse(null);

        if (organizacion != null && esAdministradorDe(actual, organizacion)) {
            return workspaces.findDeLaOrganizacion(organizacion.getId()).stream()
                    .map(w -> respuesta(w, Role.ADMIN, Role.ADMIN.permisosPorDefecto(), w.getId().equals(activo)))
                    .toList();
        }

        List<WorkspaceMember> propias = miembros.findDelUsuario(actual.getId());

        if (propias.isEmpty()) {
            // Solo pasa si el arranque todavía no completó las membresías de
            // quien existía antes de ellas. Su workspace es suyo igual.
            Workspace suyo = workspaces.findById(activo)
                    .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado."));
            return List.of(respuesta(suyo, rolDe(actual), rolDe(actual).permisosPorDefecto(), true));
        }

        return propias.stream()
                // Un espacio archivado no se ofrece: no publica, y enseñarlo
                // en el selector solo lleva a entrar y no entender por qué
                // nada sale. Quien lo administra sí lo ve, para restaurarlo.
                .filter(m -> !m.getWorkspace().archivado())
                .map(m -> respuesta(m.getWorkspace(), m.getRole(), m.permisosEfectivos(),
                        m.getWorkspace().getId().equals(activo)))
                .toList();
    }

    private boolean esAdministradorDe(User user, Organization organizacion) {
        return organizaciones.rolDe(user.getId(), organizacion.getId()) != null
                && organizaciones.rolDe(user.getId(), organizacion.getId()).administraLaOrganizacion();
    }

    /**
     * Da de alta un cliente en la organización de quien lo pide.
     *
     * <p>Quien la administra, o alguien a quien se le dio "crear espacios": un
     * cliente nuevo cuesta —cada espacio crea su perfil en upload-post, y el
     * día que se cobre por espacio, cuesta dinero— así que no es una decisión
     * de cualquiera que trabaje dentro.
     *
     * <p>No lo activa: dar de alta un cliente y seguir trabajando en el actual
     * es tan normal como pasar a él, y eso lo decide quien llama con
     * {@link #activar}.
     */
    @Transactional
    public MiWorkspaceResponse crear(User actual, String nombre) {
        WorkspaceCreateRequest solo = new WorkspaceCreateRequest();
        solo.setName(nombre);
        return crear(actual, solo);
    }

    /**
     * Da de alta un cliente con los datos de su negocio. Solo el nombre es
     * obligatorio; giro, ciudad, descripcion y objetivo son lo que la IA usa
     * para escribir como el negocio, y se guardan si vienen.
     */
    @Transactional
    public MiWorkspaceResponse crear(User actual, WorkspaceCreateRequest datos) {
        String nombre = datos == null ? null : datos.getName();
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El espacio de trabajo necesita un nombre.");
        }
        User user = users.findById(actual.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado."));

        Organization organizacion = organizaciones.deLaSesion(user);
        // Quien administra la organización, o un miembro a quien se le dio
        // "crear espacios". Ese miembro queda como ADMIN del que crea: es suyo.
        organizaciones.exigirPermiso(user, organizacion.getId(), OrgPermission.CREATE_WORKSPACES);

        long activos = workspaces.countByOrganizationIdAndArchivedAtIsNull(organizacion.getId());
        if (activos >= organizacion.getMaxWorkspaces()) {
            // Con el nombre del tope en el mensaje: quien lo lee tiene que
            // poder saber si le falta espacio o si algo se rompió.
            throw new IllegalStateException("Tu organización llegó a su tope de "
                    + organizacion.getMaxWorkspaces() + " espacios de trabajo. Archiva uno o escríbenos para ampliarlo.");
        }

        Workspace nuevo = workspaces.save(Workspace.builder()
                .name(nombre.trim())
                .giro(limpio(datos.getGiro()))
                .ciudad(limpio(datos.getCiudad()))
                .descripcion(limpio(datos.getDescripcion()))
                .objetivo(datos.getObjetivo())
                .organization(organizacion)
                .build());
        // La membresía del creador, aunque administre la organización y ya
        // entrara sin ella: así el espacio aparece en su lista de siempre.
        miembros.save(WorkspaceMember.de(user, nuevo, Role.ADMIN));

        return respuesta(nuevo, Role.ADMIN, Role.ADMIN.permisosPorDefecto(), false);
    }

    /**
     * Archiva un cliente: deja de aparecer y deja de publicar.
     *
     * <p>No se borra nada, y por eso mismo se puede deshacer. El borrado
     * definitivo —publicaciones, archivos de R2 y redes conectadas— se pide a
     * soporte: un clic no puede llevarse el contenido de un año.
     *
     * <p>El espacio activo no se archiva: dejaría a quien lo pide trabajando
     * dentro de algo que ya no publica, sin entender por qué. Primero se
     * cambia a otro.
     */
    @Transactional
    public MiWorkspaceResponse archivar(User actual, UUID workspaceId, boolean archivar) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));

        Organization organizacion = espacio.getOrganization();
        if (organizacion == null) {
            throw new ResourceNotFoundException("Espacio de trabajo no encontrado.");
        }
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        if (archivar && workspaceId.equals(actual.getWorkspace().getId())) {
            throw new IllegalStateException("Estás trabajando en este espacio. Cámbiate a otro y luego archívalo.");
        }

        // Siempre tiene que quedar uno activo. Con cero, quien entre mañana no
        // tendría dónde caer: no hay pantalla que abrir, ni redes, ni sitio
        // donde crear nada. Es el equivalente a quedarse sin la cuenta.
        if (archivar && !espacio.archivado()
                && workspaces.countByOrganizationIdAndArchivedAtIsNull(organizacion.getId()) <= 1) {
            throw new IllegalStateException("Es tu último espacio activo. Crea otro antes de archivar este.");
        }

        espacio.setArchivedAt(archivar ? LocalDateTime.now() : null);
        workspaces.save(espacio);

        return respuesta(espacio, Role.ADMIN, Role.ADMIN.permisosPorDefecto(), false);
    }

    /**
     * Pasa a trabajar en otro workspace y devuelve una sesión nueva de ese.
     *
     * <p>Un workspace sin membresía da 404 y no 403, igual que uno que no
     * existe: responder distinto le diría a cualquiera qué ids son de verdad.
     */
    @Transactional
    public AuthResponse activar(User actual, UUID workspaceId) {
        if (workspaceId == null) {
            throw new ResourceNotFoundException("Workspace no encontrado.");
        }

        Workspace destino = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado."));

        // Dos puertas, y basta con una: estar apuntado en el espacio, o
        // administrar la organización dueña. La segunda es lo que deja al
        // dueño entrar a cualquiera de sus clientes sin darse de alta en cada
        // uno; sin ella, la capa de organización no serviría de nada.
        boolean miembroDelEspacio = miembros.existsByUserIdAndWorkspaceId(actual.getId(), workspaceId);
        boolean administraLaOrganizacion = destino.getOrganization() != null
                && esAdministradorDe(actual, destino.getOrganization());

        if (!miembroDelEspacio && !administraLaOrganizacion) {
            throw new ResourceNotFoundException("Workspace no encontrado.");
        }

        if (destino.archivado()) {
            throw new IllegalStateException("Ese espacio está archivado. Restáuralo para volver a trabajar en él.");
        }

        User user = users.findById(actual.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado."));

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


    /**
     * Cambia la identidad del espacio: su nombre, su logotipo, su color y sus
     * etiquetas.
     *
     * <p>Solo quien administra la organización: el nombre y el color son cómo
     * el equipo distingue a un cliente de otro antes de publicar, y eso no lo
     * mueve cualquiera que trabaje dentro.
     */
    @Transactional
    public MiWorkspaceResponse editar(User actual, UUID workspaceId, EspacioUpdateRequest peticion) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));

        Organization organizacion = espacio.getOrganization();
        if (organizacion == null) {
            throw new ResourceNotFoundException("Espacio de trabajo no encontrado.");
        }
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        if (peticion.getName() != null && !peticion.getName().isBlank()) {
            espacio.setName(peticion.getName().strip());
        }
        // El logotipo y el color sí se pueden dejar vacíos a propósito: quitar
        // un logotipo que ya no vale es una acción legítima.
        if (peticion.getLogoUrl() != null) {
            espacio.setLogoUrl(peticion.getLogoUrl().isBlank() ? null : peticion.getLogoUrl().strip());
        }
        if (peticion.getColor() != null) {
            espacio.setColor(peticion.getColor().isBlank() ? null : peticion.getColor().strip());
        }
        if (peticion.getTags() != null) {
            espacio.setTags(peticion.etiquetas());
        }

        workspaces.save(espacio);
        return respuesta(espacio, Role.ADMIN, Role.ADMIN.permisosPorDefecto(), workspaceId.equals(actual.getWorkspace().getId()));
    }

    /**
     * Sube el logotipo de un espacio que no es, necesariamente, el activo de
     * quien lo sube — el modal grande de administrar espacios deja tocar
     * cualquiera de la organización sin cambiar a él primero.
     *
     * <p>Por eso el archivo NO se sube aquí mismo: se delega a
     * {@link WorkspaceLogoUploader}, que lo guarda a nombre de
     * {@code workspaceId} y no del espacio activo de {@code actual}. Ver ahí
     * el porqué.
     *
     * <p>No {@code @Transactional}: la comprobación de permiso es de sobra
     * con la transacción propia de {@code workspaces.findById}, y el resto
     * necesita abrir SU PROPIA transacción después de imponer el tenant, no
     * heredar una que ya estaría abierta con el tenant de {@code actual}.
     */
    public MediaAssetResponse subirLogo(User actual, UUID workspaceId, MultipartFile file) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));

        Organization organizacion = espacio.getOrganization();
        if (organizacion == null) {
            throw new ResourceNotFoundException("Espacio de trabajo no encontrado.");
        }
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        return logoUploader.subirComo(file, workspaceId);
    }

    private static MiWorkspaceResponse respuesta(Workspace workspace, Role role,
            java.util.Set<Permission> permisos, boolean activo) {
        return new MiWorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getLogoUrl(),
                workspace.getColor(),
                workspace.getTags() == null ? List.of() : List.copyOf(workspace.getTags()),
                role, permisos.stream().map(Enum::name).sorted().toList(),
                activo, workspace.archivado());
    }

    private static Role rolDe(User user) {
        return user.getRole() == null ? Role.ADMIN : user.getRole();
    }

    /** El texto sin espacios de más, o {@code null} si no quedó nada. */
    private static String limpio(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.strip();
        return recortado.isEmpty() ? null : recortado;
    }
}

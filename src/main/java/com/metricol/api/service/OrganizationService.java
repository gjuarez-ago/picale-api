package com.metricol.api.service;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Organization;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.exception.ForbiddenException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * La organización: crearla, saber cuál es la de cada quien y quién manda en
 * ella.
 *
 * <p>Se crea sola. Quien se registra no elige "crear una agencia" —a quien
 * abre una taquería esa pregunta no le dice nada— pero su organización nace
 * igual, con él de dueño y su negocio como primer espacio. El día que tome un
 * segundo cliente, la capa ya está y no hay que migrar nada.
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizaciones;
    private final OrganizationMemberRepository miembros;
    private final WorkspaceRepository workspaces;
    private final UserRepository usuarios;

    /** El tope de partida de cada organización nueva. Ver application.properties. */
    private final int topeDeEspacios;

    public OrganizationService(OrganizationRepository organizaciones, OrganizationMemberRepository miembros,
            WorkspaceRepository workspaces, UserRepository usuarios,
            @Value("${app.organizations.max-workspaces:10}") int topeDeEspacios) {
        this.organizaciones = organizaciones;
        this.miembros = miembros;
        this.workspaces = workspaces;
        this.usuarios = usuarios;
        this.topeDeEspacios = topeDeEspacios;
    }

    /**
     * Crea la organización de alguien que acaba de registrarse, con él de
     * dueño, y le cuelga su primer espacio.
     */
    @Transactional
    public Organization crearPara(User dueño, Workspace primerEspacio, String nombre) {
        Organization organizacion = organizaciones.save(Organization.builder()
                .name(nombre == null || nombre.isBlank() ? nombreDe(dueño, primerEspacio) : nombre.strip())
                .maxWorkspaces(topeDeEspacios)
                .build());

        miembros.save(OrganizationMember.de(dueño, organizacion, OrgRole.OWNER));

        if (primerEspacio != null) {
            primerEspacio.setOrganization(organizacion);
            workspaces.save(primerEspacio);
        }
        return organizacion;
    }

    /**
     * La organización en la que está trabajando, deducida de su espacio activo.
     *
     * <p>Si el espacio no tiene —viene de antes de esta capa y el arranque aún
     * no pasó por él— se le crea aquí mismo en vez de fallar. Es la misma
     * cuenta de siempre y la misma gente; negarle crear un cliente por una
     * columna que todavía no se llenó sería un error inexplicable para quien
     * lo vive. Repararlo al pasar también cubre lo que se cree mientras el
     * arranque corre.
     */
    @Transactional
    public Organization deLaSesion(User user) {
        if (user == null || user.getWorkspace() == null) {
            throw new ResourceNotFoundException("No hay un espacio de trabajo activo.");
        }
        Workspace espacio = workspaces.findById(user.getWorkspace().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));

        Organization organizacion = espacio.getOrganization();
        return organizacion != null ? organizacion : organizacionDe(espacio, user);
    }

    /**
     * La organización de un espacio huérfano: la de su dueño si ya tiene una,
     * o una nueva con él dentro.
     *
     * <p>El dueño es el miembro más antiguo —el primero en entrar es quien lo
     * creó— y si no hay ninguno, quien esté pasando por aquí.
     */
    private Organization organizacionDe(Workspace espacio, User quienPregunta) {
        User dueño = usuarios.findDueñoDe(espacio.getId()).stream().findFirst().orElse(quienPregunta);

        Organization organizacion = miembros.findDelUsuario(dueño.getId()).stream()
                .filter(m -> m.getRole() == OrgRole.OWNER)
                .map(OrganizationMember::getOrganization)
                .findFirst()
                .orElse(null);

        if (organizacion == null) {
            organizacion = organizaciones.save(Organization.builder()
                    .name(nombreDe(dueño, espacio))
                    .maxWorkspaces(topeDeEspacios)
                    .build());
            miembros.save(OrganizationMember.de(dueño, organizacion, OrgRole.OWNER));
        }

        espacio.setOrganization(organizacion);
        workspaces.save(espacio);
        return organizacion;
    }

    /** El papel de alguien en una organización, o vacío si no pertenece. */
    public OrgRole rolDe(UUID userId, UUID organizationId) {
        return miembros.findByUserIdAndOrganizationId(userId, organizationId)
                .map(OrganizationMember::getRole)
                .orElse(null);
    }

    /**
     * Corta si no administra la organización.
     *
     * <p>Lo usan crear espacios, archivarlos e invitar gente: son decisiones
     * de la agencia, no del cliente, y un MEMBER no las toma ni en los espacios
     * donde sí trabaja.
     */
    public OrganizationMember exigirAdministrador(User user, UUID organizationId) {
        OrganizationMember miembro = miembros.findByUserIdAndOrganizationId(user.getId(), organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organización no encontrada."));

        if (!miembro.getRole().administraLaOrganizacion()) {
            throw new ForbiddenException("Solo quien administra la organización puede hacer esto.");
        }
        return miembro;
    }

    /**
     * Le da organización a lo que se creó antes de que existiera esta capa.
     *
     * <p>Una organización por dueño, no por espacio: quien ya tenía tres
     * clientes se queda con los tres dentro de la misma, que es lo que
     * esperaría al abrir la pantalla. El dueño es quien tiene la membresía más
     * vieja del espacio; si no hay ninguna, se salta —no se inventa un dueño.
     *
     * <p>Correrlo dos veces no duplica nada: solo mira los espacios sin
     * organización.
     */
    @Transactional
    public int completarOrganizacionesFaltantes() {
        List<Workspace> huerfanos = workspaces.findSinOrganizacion();
        int creadas = 0;

        for (Workspace espacio : huerfanos) {
            User dueño = usuarios.findDueñoDe(espacio.getId()).stream().findFirst().orElse(null);
            if (dueño == null) {
                continue;
            }

            Organization antes = espacio.getOrganization();
            organizacionDe(espacio, dueño);
            if (antes == null && espacio.getOrganization() != null) {
                creadas++;
            }
        }
        return creadas;
    }

    /** El nombre del negocio, o el de la persona. Se puede cambiar después. */
    private static String nombreDe(User dueño, Workspace espacio) {
        if (espacio != null && espacio.getName() != null && !espacio.getName().isBlank()) {
            return espacio.getName().strip();
        }
        if (dueño != null && dueño.getName() != null && !dueño.getName().isBlank()) {
            return dueño.getName().strip();
        }
        return "Mi organización";
    }
}

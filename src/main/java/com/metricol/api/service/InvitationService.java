package com.metricol.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Invitation;
import com.metricol.api.entity.InvitationWorkspace;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.OrgPermission;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.enums.Permission;
import com.metricol.api.exception.ForbiddenException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.InvitacionRequest;
import com.metricol.api.repository.InvitationRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.auth.EmailService;
import com.metricol.api.util.Correos;

/**
 * Invitar gente a la organización y dejarla entrar cuando acepta.
 *
 * <p>El camino completo: quien administra manda una invitación con los
 * espacios y permisos ya decididos, a la persona le llega un correo con un
 * enlace, y al aceptarlo se le crean de golpe la membresía de la organización
 * y una por cada espacio asignado.
 */
@Service
public class InvitationService {

    /**
     * Cuánto vive el enlace. Una semana: suficiente para quien lo ve el lunes
     * siguiente, y poco para que un correo viejo reenviado siga abriendo la
     * cuenta de un cliente.
     */
    private static final int DIAS_DE_VIGENCIA = 7;

    private final InvitationRepository invitaciones;
    private final OrganizationMemberRepository orgMiembros;
    private final WorkspaceMemberRepository miembros;
    private final WorkspaceRepository workspaces;
    private final UserRepository usuarios;
    private final OrganizationService organizaciones;
    private final EmailService correo;
    private final PermissionService permisos;
    private final String urlDelSitio;

    private static final SecureRandom ALEATORIO = new SecureRandom();

    public InvitationService(InvitationRepository invitaciones, OrganizationMemberRepository orgMiembros,
            WorkspaceMemberRepository miembros, WorkspaceRepository workspaces, UserRepository usuarios,
            OrganizationService organizaciones, EmailService correo, PermissionService permisos,
            @Value("${app.web-url:https://picale.click}") String urlDelSitio) {
        this.invitaciones = invitaciones;
        this.orgMiembros = orgMiembros;
        this.miembros = miembros;
        this.workspaces = workspaces;
        this.usuarios = usuarios;
        this.organizaciones = organizaciones;
        this.correo = correo;
        this.permisos = permisos;
        this.urlDelSitio = urlDelSitio;
    }

    /**
     * Las que siguen esperando respuesta.
     *
     * <p>Quien administra ve todas. Quien solo tiene "invitar" ve las suyas:
     * le basta para saber a quién sigue esperando, y no tiene por qué enterarse
     * de a quién invitan los demás.
     */
    @Transactional(readOnly = true)
    public List<Invitation> pendientes(User actual) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        OrganizationMember yo = organizaciones.exigirPermiso(actual, organizacion.getId(), OrgPermission.INVITE_MEMBERS);

        List<Invitation> todas = invitaciones.findPendientes(organizacion.getId());
        if (yo.getRole().administraLaOrganizacion()) {
            return todas;
        }
        return todas.stream()
                .filter(i -> i.getInvitedBy() != null && i.getInvitedBy().getId().equals(actual.getId()))
                .toList();
    }

    /**
     * Crea la invitación y manda el correo.
     *
     * <p>Se comprueba antes lo que se puede: que no esté ya dentro, que no
     * haya otra invitación viva para ese correo, y que los espacios asignados
     * sean de esta organización —esto último no es paranoia: los ids llegan del
     * navegador, y sin comprobarlo se podría dar acceso al cliente de otra
     * agencia mandando un id ajeno.
     */
    @Transactional
    public Invitation invitar(User actual, InvitacionRequest peticion) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        OrganizationMember yo = organizaciones.exigirPermiso(actual, organizacion.getId(), OrgPermission.INVITE_MEMBERS);
        boolean administro = yo.getRole().administraLaOrganizacion();

        String email = Correos.normalizar(peticion.getEmail());
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Escribe el correo de quien quieres invitar.");
        }

        usuarios.findByEmail(email).ifPresent(persona -> {
            if (orgMiembros.findByUserIdAndOrganizationId(persona.getId(), organizacion.getId()).isPresent()) {
                throw new IllegalStateException("Esa persona ya está en tu organización.");
            }
        });

        invitaciones.findVigentePara(organizacion.getId(), email).ifPresent(previa -> {
            throw new IllegalStateException("Ya hay una invitación pendiente para ese correo. Revócala si quieres cambiarla.");
        });

        OrgRole papel = peticion.getOrgRole() == null ? OrgRole.MEMBER : peticion.getOrgRole();

        // EL CANDADO. Quien invita sin administrar la organización solo puede
        // invitar miembros. Si pudiera invitar administradores, se invitaría a
        // sí mismo con otro correo y quedaría por encima de quien le dio el
        // permiso de invitar.
        if (!administro && papel != OrgRole.MEMBER) {
            throw new ForbiddenException("Solo quien administra la organización puede invitar administradores.");
        }
        if (papel == OrgRole.OWNER) {
            throw new ForbiddenException("Una organización tiene un dueño, y ese papel no se da por invitación.");
        }

        String token = tokenNuevo();
        Invitation invitacion = Invitation.builder()
                .organization(organizacion)
                .email(email)
                .orgRole(papel)
                .tokenHash(huella(token))
                .invitedBy(actual)
                .expiresAt(LocalDateTime.now().plusDays(DIAS_DE_VIGENCIA))
                .build();

        // Un ADMIN de la organización entra a todos los espacios por su papel,
        // así que asignarle algunos sería decir una mentira: ni los limita ni
        // hacen falta.
        if (papel == OrgRole.MEMBER) {
            for (InvitacionRequest.EspacioAsignado asignado : peticion.espacios()) {
                Workspace espacio = workspaces.findById(asignado.getWorkspaceId())
                        .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));

                if (espacio.getOrganization() == null
                        || !espacio.getOrganization().getId().equals(organizacion.getId())) {
                    throw new ResourceNotFoundException("Espacio de trabajo no encontrado.");
                }

                // La otra mitad del candado: solo se da lo que uno tiene. Quien
                // no entra a un cliente no mete a nadie ahí, y quien entra como
                // editor no nombra a nadie administrador de ese cliente.
                if (!administro) {
                    Set<Permission> mios = permisos.permisosDe(actual.getId(), espacio.getId());
                    if (mios.isEmpty()) {
                        throw new ForbiddenException("Solo puedes invitar a los clientes donde tú trabajas.");
                    }
                    Set<Permission> queDaria = permisosQueDaria(asignado);
                    if (!mios.containsAll(queDaria)) {
                        throw new ForbiddenException("En " + espacio.getName()
                                + " no puedes dar permisos que tú no tienes.");
                    }
                }

                invitacion.getWorkspaces().add(InvitationWorkspace.builder()
                        .invitation(invitacion)
                        .workspace(espacio)
                        .role(asignado.rolONormal())
                        .extraPermissions(asignado.extras())
                        .deniedPermissions(asignado.negados())
                        .build());
            }
        }

        Invitation guardada = invitaciones.save(invitacion);

        correo.enviarInvitacion(email, actual.getName(), organizacion.getName(),
                enlaceDe(token), DIAS_DE_VIGENCIA);

        return guardada;
    }

    /** Se arrepintió: el enlace deja de servir aunque ya esté en el correo. */
    @Transactional
    public void revocar(User actual, UUID invitacionId) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        OrganizationMember yo = organizaciones.exigirPermiso(actual, organizacion.getId(), OrgPermission.INVITE_MEMBERS);

        Invitation invitacion = invitaciones.findById(invitacionId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitación no encontrada."));

        if (!invitacion.getOrganization().getId().equals(organizacion.getId())) {
            throw new ResourceNotFoundException("Invitación no encontrada.");
        }

        // Quien no administra solo cancela las que él mandó. 404 y no 403 con
        // las ajenas, por lo mismo que en todo lo demás: no le confirma que
        // existen.
        boolean esSuya = invitacion.getInvitedBy() != null
                && invitacion.getInvitedBy().getId().equals(actual.getId());
        if (!yo.getRole().administraLaOrganizacion() && !esSuya) {
            throw new ResourceNotFoundException("Invitación no encontrada.");
        }

        invitacion.setRevokedAt(LocalDateTime.now());
        invitaciones.save(invitacion);
    }

    /** La invitación de un token, para enseñar quién invita y a qué. */
    @Transactional(readOnly = true)
    public Invitation porToken(String token) {
        Invitation invitacion = invitaciones.findByTokenHash(huella(token))
                .orElseThrow(() -> new ResourceNotFoundException("Esta invitación ya no existe."));

        if (!invitacion.vigente()) {
            throw new IllegalStateException("Esta invitación ya se usó o venció. Pídele otra a quien te invitó.");
        }
        return invitacion;
    }

    /**
     * Mete a la persona en la organización y en los espacios de la invitación.
     *
     * <p>Quien llama ya comprobó quién es: o creó la cuenta con este correo, o
     * entró con su contraseña (ver {@code InvitationController}). Este método
     * no autentica a nadie — con el token solo no se entra, porque un enlace
     * reenviado no puede dar acceso a la cuenta de otro.
     */
    @Transactional
    public void aceptar(Invitation invitacion, User persona) {
        if (!invitacion.vigente()) {
            throw new IllegalStateException("Esta invitación ya se usó o venció.");
        }

        Organization organizacion = invitacion.getOrganization();

        if (orgMiembros.findByUserIdAndOrganizationId(persona.getId(), organizacion.getId()).isEmpty()) {
            orgMiembros.save(com.metricol.api.entity.OrganizationMember.de(persona, organizacion,
                    invitacion.getOrgRole()));
        }

        for (InvitationWorkspace asignado : invitacion.getWorkspaces()) {
            if (miembros.findByUserIdAndWorkspaceId(persona.getId(), asignado.getWorkspace().getId()).isPresent()) {
                continue;
            }
            miembros.save(WorkspaceMember.builder()
                    .user(persona)
                    .workspace(asignado.getWorkspace())
                    .role(asignado.getRole())
                    .extraPermissions(asignado.getExtraPermissions())
                    .deniedPermissions(asignado.getDeniedPermissions())
                    .build());
        }

        invitacion.setAcceptedAt(LocalDateTime.now());
        invitaciones.save(invitacion);
    }

    /**
     * El primer espacio que verá al entrar.
     *
     * <p>Quien acepta tiene que caer en algún sitio, y su cuenta nueva no
     * tiene espacio propio: se le pone el primero que le asignaron. A un ADMIN
     * de la organización, que no trae asignaciones, cualquiera de la
     * organización le sirve.
     */
    @Transactional(readOnly = true)
    public Workspace primerEspacioDe(Invitation invitacion) {
        if (!invitacion.getWorkspaces().isEmpty()) {
            return invitacion.getWorkspaces().get(0).getWorkspace();
        }
        return workspaces.findDeLaOrganizacion(invitacion.getOrganization().getId()).stream()
                .filter(w -> !w.archivado())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Esta organización no tiene ningún espacio activo al que entrar."));
    }

    /**
     * Lo que tendría la persona en ese espacio si aceptara: su rol, más lo
     * dado, menos lo quitado. Es la misma cuenta que hace WorkspaceMember, y
     * se hace aquí para compararla con lo que tiene quien invita.
     */
    private static Set<Permission> permisosQueDaria(InvitacionRequest.EspacioAsignado asignado) {
        Set<Permission> resultado = EnumSet.noneOf(Permission.class);
        resultado.addAll(asignado.rolONormal().permisosPorDefecto());
        resultado.addAll(asignado.extras());
        resultado.removeAll(asignado.negados());
        return resultado;
    }

    /** 32 bytes al azar, en base64 para la URL. Solo existe en el correo. */
    private static String tokenNuevo() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 en hexadecimal: lo único que se guarda del token. */
    static String huella(String token) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 es obligatorio en toda JVM: si falta, algo más grave pasa.
            throw new IllegalStateException("No se pudo calcular la huella del token.", ex);
        }
    }

    private String enlaceDe(String token) {
        String base = urlDelSitio == null || urlDelSitio.isBlank() ? "https://picale.click" : urlDelSitio.trim();
        return base.replaceAll("/+$", "") + "/invitacion?token=" + token;
    }
}

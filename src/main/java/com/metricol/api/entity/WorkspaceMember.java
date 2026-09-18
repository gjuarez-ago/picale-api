package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.metricol.api.entity.converter.PermissionSetConverter;
import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un usuario tiene acceso a un workspace.
 *
 * <p>Es lo que permite que una persona —una agencia, un community manager—
 * lleve varios clientes, cada uno en su workspace: con su perfil de
 * upload-post, sus redes, su contenido, sus cuotas y su gasto de IA separados.
 *
 * <p>{@link User#getWorkspace()} sigue existiendo y ahora significa "el
 * workspace ACTIVO": es el que resuelve el tenant en cada petición, así que
 * todo lo que ya filtraba por workspace sigue funcionando sin tocarse. Esta
 * tabla dice a cuáles se puede cambiar.
 *
 * <p>Sin TenantId: se consulta justo para ver los workspaces de un usuario por
 * encima del activo, y el filtro automático solo dejaría ver ese.
 */
@Entity
@Table(name = "workspace_members", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_member", columnNames = {
        "user_id", "workspace_id" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    /**
     * El rol de esta persona EN este workspace: es lo que le da sus permisos
     * de partida. El mismo usuario puede ser ADMIN en un cliente y VIEWER en
     * otro, que es justo lo que hace falta en una agencia.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /**
     * Permisos dados a ESTA persona por encima de su rol.
     *
     * <p>Para el caso que el rol no prevé: "un editor que además conecta las
     * redes de este cliente". Sin esto habría que inventar un rol nuevo por
     * cada excepción.
     */
    @Builder.Default
    @Convert(converter = PermissionSetConverter.class)
    @Column(name = "extra_permissions", length = 500)
    private Set<Permission> extraPermissions = EnumSet.noneOf(Permission.class);

    /**
     * Permisos quitados a ESTA persona aunque su rol los traiga.
     *
     * <p>Gana sobre todo lo demás: si un permiso está aquí, no lo tiene, venga
     * de donde venga. Quitar es más delicado que dar —se usa para cerrar algo
     * ya concedido— así que la regla es que negar siempre gane.
     */
    @Builder.Default
    @Convert(converter = PermissionSetConverter.class)
    @Column(name = "denied_permissions", length = 500)
    private Set<Permission> deniedPermissions = EnumSet.noneOf(Permission.class);

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Lo que esta persona puede hacer de verdad: lo del rol, más lo suyo,
     * menos lo negado.
     *
     * <p>Es la única fuente de la respuesta. Todo lo que pregunte "¿puede
     * publicar?" —el servidor al recibir la petición y las dos interfaces al
     * pintar— termina aquí, para que no haya dos reglas distintas.
     */
    public Set<Permission> permisosEfectivos() {
        Set<Permission> efectivos = EnumSet.noneOf(Permission.class);
        efectivos.addAll(role == null ? Role.VIEWER.permisosPorDefecto() : role.permisosPorDefecto());
        if (extraPermissions != null) {
            efectivos.addAll(extraPermissions);
        }
        if (deniedPermissions != null) {
            efectivos.removeAll(deniedPermissions);
        }
        return efectivos;
    }

    public boolean puede(Permission permiso) {
        return permisosEfectivos().contains(permiso);
    }

    public static WorkspaceMember de(User user, Workspace workspace, Role role) {
        return WorkspaceMember.builder()
                .user(user)
                .workspace(workspace)
                .role(role == null ? Role.ADMIN : role)
                .build();
    }
}

package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.Role;

import jakarta.persistence.Column;
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
     * El rol de esta persona EN este workspace. Hoy todos son ADMIN, igual que
     * {@link User#getRole()}; va aquí para cuando se invite a alguien con menos
     * permisos a un solo cliente sin dárselos en todos.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public static WorkspaceMember de(User user, Workspace workspace, Role role) {
        return WorkspaceMember.builder()
                .user(user)
                .workspace(workspace)
                .role(role == null ? Role.ADMIN : role)
                .build();
    }
}

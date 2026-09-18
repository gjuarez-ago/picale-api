package com.metricol.api.entity;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Uno de los espacios que trae una invitación, con el rol y los permisos que
 * tendrá esa persona ahí.
 *
 * <p>Es el mismo trío que {@link WorkspaceMember} —rol, permisos dados,
 * permisos quitados— porque al aceptar se convierte exactamente en eso. Se
 * guarda aparte y no como una membresía apagada para que nadie cuente como
 * miembro de un espacio al que todavía no ha entrado.
 */
@Entity
@Table(name = "invitation_workspaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvitationWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitation_id", nullable = false)
    private Invitation invitation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Builder.Default
    @Convert(converter = PermissionSetConverter.class)
    @Column(name = "extra_permissions", length = 500)
    private Set<Permission> extraPermissions = EnumSet.noneOf(Permission.class);

    @Builder.Default
    @Convert(converter = PermissionSetConverter.class)
    @Column(name = "denied_permissions", length = 500)
    private Set<Permission> deniedPermissions = EnumSet.noneOf(Permission.class);
}

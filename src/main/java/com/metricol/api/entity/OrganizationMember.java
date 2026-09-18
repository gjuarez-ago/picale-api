package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.metricol.api.entity.converter.OrgPermissionSetConverter;
import com.metricol.api.enums.OrgPermission;
import com.metricol.api.enums.OrgRole;

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
 * Una persona pertenece a una organización, con su papel en ella.
 *
 * <p>Es la puerta de arriba. La de abajo —a qué clientes entra y qué puede
 * hacer en cada uno— es {@link WorkspaceMember}, y solo hace falta para los
 * MEMBER: quien administra la organización entra a todos sus espacios sin
 * estar apuntado en ninguno.
 */
@Entity
@Table(name = "organization_members", uniqueConstraints = @UniqueConstraint(name = "uk_org_member", columnNames = {
        "user_id", "organization_id" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrgRole role;

    /**
     * Lo que puede hacer en la organización sin administrarla: invitar gente
     * o crear espacios. Dueño y administradores lo tienen todo por su papel,
     * así que para ellos esto no se mira.
     */
    @Builder.Default
    @Convert(converter = OrgPermissionSetConverter.class)
    @Column(length = 200)
    private Set<OrgPermission> permissions = EnumSet.noneOf(OrgPermission.class);

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** ¿Puede hacer esto en la organización? Por su papel, o porque se lo dieron. */
    public boolean puede(OrgPermission permiso) {
        if (role != null && role.administraLaOrganizacion()) {
            return true;
        }
        return permissions != null && permissions.contains(permiso);
    }

    public static OrganizationMember de(User user, Organization organization, OrgRole role) {
        return OrganizationMember.builder()
                .user(user)
                .organization(organization)
                .role(role == null ? OrgRole.MEMBER : role)
                .build();
    }
}

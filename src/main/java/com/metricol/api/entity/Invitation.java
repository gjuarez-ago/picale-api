package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.OrgRole;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una invitación a entrar a la organización, con los espacios que se le
 * asignan y lo que podrá hacer en cada uno.
 *
 * <p>La invitación es a la ORGANIZACIÓN y no a un espacio, aunque traiga
 * espacios dentro. Invitar por espacio significaría tres correos para alguien
 * que va a llevar tres clientes, y tres veces la misma pregunta de "¿aceptas?"
 * para una sola decisión. Aquí se acepta una vez y queda dentro de todo lo que
 * se le asignó.
 *
 * <p>Los permisos viajan decididos desde el principio: quien invita sabe en
 * ese momento para qué quiere a esa persona, y así nadie pasa un rato dentro
 * de la cuenta de un cliente con permisos de más mientras alguien se acuerda
 * de ajustarlos.
 *
 * <p><b>Del token solo se guarda su huella.</b> El enlace lleva el token en
 * claro y la base guarda su SHA-256, igual que una contraseña: si la tabla se
 * filtra, con lo que hay ahí no se entra. El original solo existe en el correo
 * de la persona invitada.
 */
@Entity
@Table(name = "invitations", indexes = {
        @Index(name = "ix_invitation_token", columnList = "token_hash", unique = true),
        @Index(name = "ix_invitation_org", columnList = "organization_id") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** En minúsculas, como se guardan los correos de los usuarios. */
    @Column(nullable = false, length = 180)
    private String email;

    /** Su papel en la organización. Casi siempre MEMBER: un subordinado. */
    @Enumerated(EnumType.STRING)
    @Column(name = "org_role", nullable = false, length = 20)
    private OrgRole orgRole;

    /**
     * A qué espacios entra y con qué permisos en cada uno.
     *
     * <p>Vacío cuando se invita a un ADMIN de la organización: ese entra a
     * todos sin que haga falta apuntarlo en ninguno.
     */
    @Builder.Default
    @OneToMany(mappedBy = "invitation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<InvitationWorkspace> workspaces = new ArrayList<>();

    /** SHA-256 del token del enlace. Ver la nota de la clase. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private User invitedBy;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** Con fecha, ya se usó: una invitación no sirve dos veces. */
    private LocalDateTime acceptedAt;

    /** Con fecha, quien invitó se arrepintió antes de que la usaran. */
    private LocalDateTime revokedAt;

    /** Ni usada, ni revocada, ni vencida. */
    public boolean vigente() {
        return acceptedAt == null
                && revokedAt == null
                && expiresAt != null
                && expiresAt.isAfter(LocalDateTime.now());
    }
}

package com.metricol.api.entity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

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
 * Una persona con acceso.
 *
 * <p>El correo es único <b>en la base</b>, no solo en la comprobación del
 * registro. Aquella comprobación llega tarde cuando dos registros del mismo
 * correo entran a la vez —los dos pasan el {@code existsByEmail}, los dos se
 * guardan— y desde ese momento {@code findByEmail} devuelve dos filas y entrar
 * es un 500. La restricción es lo que hace imposible el segundo, y el registro
 * la atrapa para contestar lo de siempre: «ya existe una cuenta con ese
 * correo». Va siempre normalizado por {@code Correos.normalizar}.
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    /** La autoridad de quien administra la plataforma (ver {@link #platformAdmin}). */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    @Column(nullable = false, length = 180)
    private String email;

    private String password;

    @Enumerated(EnumType.STRING)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    /**
     * Administra la PLATAFORMA, no un workspace ni una organización: ve y
     * ajusta todas las organizaciones, sus licencias y sus créditos desde
     * {@code /api/v1/root/**}.
     *
     * <p>No es un {@link Role}: aquel dice qué puede hacer dentro de su espacio,
     * y esto es otra dimensión. Lo enciende el arranque para la cuenta raíz, y
     * para las demás lo da o lo quita solo quien ya lo tiene, desde
     * {@code /api/v1/root/administradores}: ningún permiso de organización
     * escala hasta aquí.
     */
    @Builder.Default
    @Column(name = "platform_admin", nullable = false, columnDefinition = "boolean default false")
    private boolean platformAdmin = false;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (platformAdmin) {
            // Ver SecurityConfig: /api/v1/root/** exige esta autoridad.
            return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()),
                    new SimpleGrantedAuthority(PLATFORM_ADMIN));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

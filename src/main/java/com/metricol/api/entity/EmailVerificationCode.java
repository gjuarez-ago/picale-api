package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.metricol.api.entity.converter.VerificationPurposeConverter;
import com.metricol.api.enums.VerificationPurpose;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un código de seis dígitos, de un solo uso, para comprobar que quien escribió
 * un correo de verdad lo lee.
 *
 * <p>Se liga al correo y no al {@code User} a propósito: lo que se está
 * comprobando es el correo. Y no lleva {@code @TenantId}: quien pide
 * restablecer su contraseña no tiene sesión, así que no hay workspace del que
 * colgarlo, y filtrarlo por tenant lo dejaría invisible justo para el único
 * flujo que lo usa.
 *
 * <p>El código nunca se guarda en claro. Una fuga de esta tabla no debe
 * entregar códigos usables, así que lo que queda es su hash BCrypt, igual que
 * las contraseñas.
 */
@Entity
@Table(name = "email_verification_codes", indexes = {
        @Index(name = "idx_verification_email_purpose", columnList = "email, purpose")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Ya normalizado por {@code Correos.normalizar}: minúsculas, sin espacios. */
    @Column(nullable = false, length = 180)
    private String email;

    @Convert(converter = VerificationPurposeConverter.class)
    @Column(nullable = false, length = 32)
    private VerificationPurpose purpose;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Intentos fallidos. Es lo que corta la fuerza bruta sobre un millón de combinaciones. */
    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean consumed = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}

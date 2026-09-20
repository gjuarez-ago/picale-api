package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.LicenseStatus;

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
 * La licencia de un workspace: lo que da derecho a usarlo.
 *
 * <p>Una licencia por workspace. Cada una es una suscripción de Stripe con su
 * propia fecha: se paga el mes completo al comprarla, y cancelar solo apaga la
 * renovación —se sigue usando hasta que termine lo ya pagado—. Sin
 * reembolsos, que es lo que impide aprovecharse (comprar, usar y devolver).
 *
 * <p>Sin {@code @TenantId}: la leen los workers y los avisos de Stripe, que no
 * tienen tenant. Cuando una licencia termina, el workspace se <b>archiva</b>
 * (no se borra nada) y al pagar se restaura.
 */
@Entity
@Table(name = "licenses", indexes = {
        @Index(name = "ix_licenses_org", columnList = "organization_id"),
        @Index(name = "ix_licenses_estado", columnList = "status") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class License {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "workspace_id", nullable = false, unique = true)
    private UUID workspaceId;

    @Convert(converter = LicenseStatusConverter.class)
    @Column(nullable = false, length = 20)
    private LicenseStatus status;

    /** Hasta cuándo dura la prueba gratis. Solo tiene sentido en {@code TRIALING}. */
    private LocalDateTime trialEndsAt;

    /** Cuándo termina lo ya pagado (y cuándo se renueva). */
    private LocalDateTime currentPeriodEnd;

    /** Hasta cuándo se sigue usando tras un cobro fallido. Solo en {@code PAST_DUE}. */
    private LocalDateTime graceUntil;

    /** El {@code sub_...} de Stripe. Nulo mientras es una prueba sin tarjeta. */
    @Column(length = 80, unique = true)
    private String stripeSubscriptionId;

    /** La persona pidió que no se renueve: se usa hasta {@link #currentPeriodEnd} y termina. */
    @Builder.Default
    @Column(nullable = false)
    private boolean cancelAtPeriodEnd = false;

    /**
     * Esta suscripción se cobra al precio de un negocio ADICIONAL (no al del
     * plan). Sirve para que, si termina la licencia que llevaba el precio
     * completo, otra tome su lugar (ver {@link com.metricol.api.service.billing.LicensePricingService}).
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean pricedAsExtra = false;

    /**
     * El workspace lo archivó el proceso de vencimientos (no la persona). Solo
     * esos se restauran solos al pagar: uno que alguien archivó a propósito se
     * queda como está.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean archivedBySweep = false;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * ¿Se puede usar el workspace ahora?
     *
     * <p>Una licencia pagada tiene un día de holgura tras el fin del periodo:
     * la renovación llega como aviso de Stripe, y unos minutos de retraso no
     * deben cerrarle el workspace a quien ya pagó.
     */
    public boolean usable(LocalDateTime ahora) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case TRIALING -> trialEndsAt != null && ahora.isBefore(trialEndsAt);
            case ACTIVE -> currentPeriodEnd != null && ahora.isBefore(currentPeriodEnd.plusDays(1));
            case PAST_DUE -> graceUntil != null && ahora.isBefore(graceUntil);
            case ENDED -> false;
        };
    }
}

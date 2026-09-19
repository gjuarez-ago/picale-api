package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Los créditos de imagen de un workspace: 1 crédito = 1 generación (todas las
 * versiones que salgan de ella).
 *
 * <p>Dos bolsas. Los <b>mensuales</b> vienen con la licencia, se reinician al
 * renovarse y no se acumulan. Los de <b>paquete</b> se compran sueltos y no
 * vencen. Al generar se gastan primero los mensuales.
 *
 * <p>Sin {@code @TenantId}: los suman los avisos de Stripe, que no tienen tenant.
 */
@Entity
@Table(name = "image_credits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageCredits {

    @Id
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Builder.Default
    @Column(nullable = false)
    private int monthlyBalance = 0;

    /** Cuándo se pierden los mensuales que sobren (el fin del periodo de la licencia). */
    private LocalDateTime monthlyExpiresAt;

    @Builder.Default
    @Column(nullable = false)
    private int packBalance = 0;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Los mensuales que todavía valen. */
    public int mensualesVigentes(LocalDateTime ahora) {
        boolean vigente = monthlyExpiresAt == null || ahora.isBefore(monthlyExpiresAt);
        return vigente ? Math.max(0, monthlyBalance) : 0;
    }

    public int total(LocalDateTime ahora) {
        return mensualesVigentes(ahora) + Math.max(0, packBalance);
    }
}

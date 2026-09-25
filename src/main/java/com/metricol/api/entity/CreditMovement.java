package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cada vez que cambia el saldo de créditos, con el porqué.
 *
 * <p>Es el libro de cuentas: el saldo se puede reconstruir sumando esto. La
 * combinación (workspace, motivo, referencia) es única cuando hay referencia:
 * así un aviso de Stripe que llega dos veces, o una devolución repetida, no
 * suma dos veces.
 */
@Entity
@Table(name = "credit_movements",
        indexes = @Index(name = "ix_credit_mov_ws", columnList = "workspace_id, created_at"),
        uniqueConstraints = @UniqueConstraint(name = "ux_credit_mov_ref",
                columnNames = { "workspace_id", "motivo", "referencia" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditMovement {

    public static final String MENSUAL = "MONTHLY";
    public static final String PAQUETE = "PACK";

    public static final String OTORGADO_MENSUAL = "GRANT_MONTHLY";
    public static final String COMPRA_PAQUETE = "PACK_PURCHASE";
    public static final String GENERACION = "GENERATION";
    public static final String DEVOLUCION = "REFUND";
    public static final String AJUSTE = "ADJUSTMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Positivo suma, negativo gasta. */
    @Column(nullable = false)
    private int delta;

    /** {@link #MENSUAL} o {@link #PAQUETE}: de qué bolsa salió o a cuál entró. */
    @Column(nullable = false, length = 10)
    private String bolsa;

    @Column(nullable = false, length = 20)
    private String motivo;

    /** El identificador del hecho (factura, compra, generación) para no contarlo dos veces. */
    @Column(length = 80)
    private String referencia;

    /**
     * Por qué, con palabras, cuando alguien lo escribió: el motivo de un ajuste a
     * mano desde la administración de la plataforma. Nulo en los movimientos
     * automáticos, que ya se explican con {@link #motivo} y {@link #referencia}.
     * Sin esto el historial enseñaba un AJUSTE pelado con una referencia opaca.
     */
    @Column(length = 60)
    private String nota;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

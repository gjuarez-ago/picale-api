package com.metricol.api.entity;

import java.time.LocalDateTime;

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
 * Un aviso de Stripe que ya se procesó.
 *
 * <p>Stripe repite los avisos cuando no se le contesta a tiempo, y a veces
 * manda el mismo dos veces. Recordar el {@code evt_...} hace que un aviso
 * repetido no dé dos licencias ni dos paquetes de créditos.
 */
@Entity
@Table(name = "stripe_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StripeEvent {

    /** El {@code evt_...} de Stripe. */
    @Id
    @Column(length = 80)
    private String id;

    @Column(nullable = false, length = 80)
    private String type;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime processedAt = LocalDateTime.now();
}

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
 * Un ajuste de los cobros, editable en la base sin desplegar.
 *
 * <p>Aquí vive lo que cambia con el negocio y no con el código: si los cobros
 * están encendidos, cuántos días de prueba, cuántos de gracia, cuántos créditos
 * trae cada licencia y qué precio de Stripe se usa. Cambiar cualquiera es un
 * {@code UPDATE} (o {@code PUT /api/v1/ops/billing/settings/{clave}}), y en
 * medio minuto lo lee todo el mundo.
 *
 * <p>Igual que {@code app_limits}: las propiedades de entorno solo <b>siembran</b>
 * la tabla la primera vez, y una fila que ya existe no se vuelve a tocar.
 * Valores como texto para que quepan números, banderas y el id de un precio.
 */
@Entity
@Table(name = "billing_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingSetting {

    /** «billing.enabled», «billing.trial_days.signup»… Ver las constantes de BillingConfig. */
    @Id
    @Column(length = 80)
    private String clave;

    @Column(nullable = false, length = 300)
    private String valor;

    @Column(length = 400)
    private String descripcion;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}

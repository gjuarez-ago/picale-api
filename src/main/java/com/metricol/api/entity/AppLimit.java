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
 * Un tope de la plataforma, editable en la base sin desplegar.
 *
 * <p>Los topes —cuántas publicaciones al día por red, cuántas puede dejar
 * pendientes un workspace, cuántas llamadas a la IA— vivían en variables de
 * entorno, y cambiar uno era reconstruir la imagen y reiniciar el contenedor.
 * Aquí un {@code UPDATE} basta, y en medio minuto lo lee todo el mundo (ver la
 * caché de {@code LimitesConfigurables}).
 *
 * <p>Las variables de entorno siguen existiendo pero solo como <b>semilla</b>:
 * llenan la tabla la primera vez que arranca la aplicación y no vuelven a
 * tocar una fila que ya existe. A partir de ese momento manda la tabla.
 *
 * <p>Sin {@code @TenantId}: son topes de la plataforma, no de un workspace, y
 * los leen los workers, que no tienen tenant.
 */
@Entity
@Table(name = "app_limits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppLimit {

    /** «quota.daily.INSTAGRAM», «limits.posts.max_pending»… Ver las constantes de LimitesConfigurables. */
    @Id
    @Column(length = 80)
    private String clave;

    /** El número. Un 0 significa «sin tope» en las claves donde eso tiene sentido. */
    @Column(nullable = false)
    private long valor;

    /** Para quien abra la tabla sin el código a la mano. */
    @Column(length = 300)
    private String descripcion;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}

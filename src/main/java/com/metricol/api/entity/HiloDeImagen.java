package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
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
 * Una conversación para crear una imagen.
 *
 * <p>Guarda la {@code ficha} —lo que se ha entendido hasta ahora— como JSON, y
 * no en columnas: la ficha va a cambiar de campos conforme se aprenda qué hace
 * falta de verdad, y una migración por cada campo nuevo frenaría justo lo que
 * hay que poder ajustar rápido. Lo que se consulta de verdad (de quién es, en
 * qué espacio, cuándo) sí va en columnas.
 */
@Entity
@Table(name = "hilos_de_imagen",
        indexes = @Index(name = "ix_hilos_imagen_espacio", columnList = "workspace_id, actualizado_en"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HiloDeImagen {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Quién lo abrió. La conversación es suya. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** La ficha, como JSON. Ver {@code FichaDeImagen}. */
    @Column(columnDefinition = "text")
    private String ficha;

    /**
     * Cuántas veces se creó de verdad en este hilo.
     *
     * <p>Sirve para dos cosas: enseñar "van 2 creaciones" y detectar el hilo
     * que se atascó dando vueltas sin acertar, que es la señal de que el
     * asistente no está entendiendo.
     */
    @Builder.Default
    private int creaciones = 0;

    /**
     * El trabajo de creación que sigue en marcha, si lo hay.
     *
     * <p>Sin esto, salirse de la pantalla mientras se crea perdía las
     * imágenes: el sondeo vivía en el teléfono, y al volver la conversación no
     * sabía que había algo en camino. Se pagaron y se quedaron solo en
     * Contenido, sin que nadie lo dijera. Con esto, al reabrir se reanuda.
     */
    @Column(length = 64)
    private String trabajoEnCurso;

    private LocalDateTime creadoEn;

    private LocalDateTime actualizadoEn;

    /** Se cerró al usar una imagen o al abandonarlo. Null = sigue abierto. */
    private LocalDateTime cerradoEn;

    /**
     * La persona la quitó de su historial. Null = sigue ahí.
     *
     * <p>No se borra de verdad: lo que se creó en esa conversación vive en
     * Contenido y se pagó con créditos, así que quitar el chat no puede
     * llevarse las imágenes por delante. Esto solo la saca de la lista.
     */
    private LocalDateTime borradoEn;
}

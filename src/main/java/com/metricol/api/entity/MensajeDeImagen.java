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

/** Un turno de la conversación: lo que dijo la persona o lo que contestó el asistente. */
@Entity
@Table(name = "mensajes_de_imagen",
        indexes = @Index(name = "ix_mensajes_imagen_hilo", columnList = "hilo_id, creado_en"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MensajeDeImagen {

    /** Tope del texto de un turno. De sobra para dictar un párrafo largo. */
    public static final int MAX_TEXTO = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "hilo_id", nullable = false)
    private UUID hiloId;

    /** PERSONA o ASISTENTE. */
    @Column(nullable = false, length = 20)
    private String rol;

    @Column(length = MAX_TEXTO)
    private String texto;

    /**
     * Las respuestas que se pueden TOCAR, como JSON (una lista de textos).
     *
     * <p>Es lo que evita que esto sea un formulario con burbujas. Una pregunta
     * abierta delante de alguien que no sabe qué se espera es donde se
     * abandona; tres botones y un "otra cosa" se contestan sin pensar.
     */
    @Column(columnDefinition = "text")
    private String opciones;

    /**
     * Qué dijo el filtro de este turno: VA, OBSERVACION o DESCARTADA.
     *
     * <p>Los mismos tres veredictos que usa el agente con las fotos y los
     * videos ({@code RevisorDeMarca}), a propósito: lo que no pasa aquí
     * tampoco debería pasar allá.
     */
    @Column(length = 20)
    private String veredicto;

    /** Por qué, cuando el filtro no dejó pasar algo. En palabras de la persona. */
    @Column(length = 400)
    private String motivo;

    private LocalDateTime creadoEn;
}

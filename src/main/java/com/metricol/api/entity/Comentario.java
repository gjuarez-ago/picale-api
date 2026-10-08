package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.metricol.api.enums.Platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Un comentario que alguien dejó en una publicación nuestra, traído de la red
 * y guardado para poder contestarlo desde un solo lugar.
 *
 * <p>La fila manda sobre la red en una sola cosa: el estado de la bandeja
 * (leído, atendido, por quién). Lo demás —el texto, quién lo escribió— es una
 * copia de lo que dijo la red cuando se leyó, y no se vuelve a tocar: si la
 * persona edita su comentario en Instagram, lo que vemos es lo que había. Es a
 * propósito; así queda constancia de a qué se contestó.
 */
@Entity
@Table(name = "comentarios",
        uniqueConstraints = @UniqueConstraint(name = "uk_comentario_en_la_red",
                columnNames = {"red", "id_en_la_red"}),
        indexes = {
                @Index(name = "ix_comentarios_bandeja", columnList = "workspace_id, atendido_en, escrito_en"),
                @Index(name = "ix_comentarios_destino", columnList = "post_target_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comentario {

    /** Tope del texto. Instagram corta en 2200; se deja holgado y se recorta al guardar. */
    public static final int MAX_TEXTO = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "social_account_id")
    private UUID socialAccountId;

    /** El destino (publicación + red) donde se dejó. */
    @Column(name = "post_target_id", nullable = false)
    private UUID postTargetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform red;

    /**
     * El identificador que le da la red. Junto con {@link #red} es único: es lo
     * único que impide guardar dos veces el mismo comentario y, sobre todo,
     * avisar dos veces por él.
     */
    @Column(name = "id_en_la_red", nullable = false, length = 255)
    private String idEnLaRed;

    /** El comentario al que contesta, si contesta a otro. Null si cuelga de la publicación. */
    @Column(name = "padre_id_en_la_red", length = 255)
    private String padreIdEnLaRed;

    @Column(length = 255)
    private String autorNombre;

    /** Mismo tope que {@link SocialAccount#MAX_AVATAR_URL}: son URLs firmadas y larguísimas. */
    @Column(length = SocialAccount.MAX_AVATAR_URL)
    private String autorAvatarUrl;

    @Column(length = MAX_TEXTO)
    private String texto;

    /**
     * La foto con la que comentaron, si comentaron con una.
     *
     * <p>Pasa más de lo que parece: en Facebook se contesta con una imagen y
     * {@code message} viene vacío. Sin esto la bandeja enseñaba una fila con
     * nombre y hora y nada más, como si el comentario no dijera nada — y sí
     * decía, solo que con una foto.
     */
    @Column(length = SocialAccount.MAX_AVATAR_URL)
    private String adjuntoUrl;

    /** El comentario en la propia red, para poder ir a verlo ahí. */
    @Column(length = SocialAccount.MAX_AVATAR_URL)
    private String enlace;

    /** Cuándo lo escribió la persona en la red (no cuándo lo leímos nosotros). */
    private LocalDateTime escritoEn;

    /** Cuándo lo trajimos. Sirve para saber qué entró en la última vuelta. */
    private LocalDateTime traidoEn;

    /**
     * Es nuestro: lo escribió la propia cuenta (una respuesta anterior, hecha
     * aquí o directamente en la red). Nunca es un pendiente ni genera aviso —
     * nadie se avisa a sí mismo.
     */
    @Builder.Default
    private boolean propio = false;

    private LocalDateTime leidoEn;

    /** Cuando está puesto, el comentario sale de "Pendientes". */
    private LocalDateTime atendidoEn;

    private UUID atendidoPor;

    @Column(length = MAX_TEXTO)
    private String respuestaTexto;

    private LocalDateTime respondidoEn;

    @Column(name = "respuesta_id_en_la_red", length = 255)
    private String respuestaIdEnLaRed;

    /** Lo ocultamos o lo borramos en la red, o dejó de venir. */
    private LocalDateTime ocultoEn;

    /** Para contestar hace falta el id de la publicación en la red; se guarda al traerlo. */
    @Column(name = "post_id_en_la_red", length = 255)
    private String postIdEnLaRed;

    /** Dijo algo, aunque sea con una foto. */
    public boolean vacio() {
        return (texto == null || texto.isBlank()) && (adjuntoUrl == null || adjuntoUrl.isBlank());
    }

    public boolean pendiente() {
        return atendidoEn == null && !propio && ocultoEn == null;
    }

    /**
     * ¿Parece una pregunta? Es lo que mira el ajuste "Solo preguntas" del
     * teléfono. Deliberadamente tonto y sin IA: tiene que poder decidirse
     * mientras se guarda, sin costar nada y sin equivocarse de forma rara.
     */
    public boolean pareceSerPregunta() {
        if (texto == null) {
            return false;
        }
        String t = texto.toLowerCase();
        if (t.contains("?") || t.contains("¿")) {
            return true;
        }
        for (String palabra : new String[] {"cuanto", "cuánto", "precio", "costo", "cuesta", "donde", "dónde",
                "como", "cómo", "cuando", "cuándo", "tienen", "hay ", "envian", "envían", "disponible"}) {
            if (t.contains(palabra)) {
                return true;
            }
        }
        return false;
    }
}

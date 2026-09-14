package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.PostTargetStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "post_targets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "social_account_id", nullable = false)
    private SocialAccount socialAccount;

    /**
     * El texto con el que se publica en ESTA red.
     *
     * <p>Nulo significa "usa el de la publicación". No es lo mismo que vacío:
     * antes solo existía {@code Post.caption} y el mismo párrafo salía en las
     * cinco redes, que es justo lo que delata una cuenta automatizada — y lo
     * que no cabe, porque en TikTok son 90 caracteres y en LinkedIn 3000.
     */
    @Column(length = 3000)
    private String caption;

    /**
     * El texto que de verdad salió a esta red, tal cual se mandó.
     *
     * <p>No es {@link #caption}, y la diferencia es justo lo que hace falta
     * ver: {@code caption} es lo que se escribió, y esto es lo que llegó. Entre
     * los dos pasa el recorte por red —Facebook admite 255 en el título y
     * TikTok 90—, así que en un texto largo no se parecen.
     *
     * <p>Se guarda porque no se puede reconstruir. Recalcularlo al leerlo daría
     * el recorte de HOY, con los topes de hoy, y no el que se aplicó aquel día;
     * enseñar eso como "lo que se mandó" sería mentir con aire de dato.
     *
     * <p>{@code null} en lo publicado antes de que existiera esta columna, y en
     * lo que todavía no ha salido.
     */
    @Column(length = 4000)
    private String captionEnviado;

    @Enumerated(EnumType.STRING)
    private PostTargetStatus status;

    private LocalDateTime publishedAt;

    /** Id del post ya publicado que devuelve upload-post.com, si lo hay. */
    private String externalPostId;

    /**
     * Enlace a la publicación tal como quedó en la red, cuando el proveedor lo
     * devuelve.
     *
     * <p>Es lo que convierte el calendario en algo útil después de publicar:
     * sin esto, "ver la publicación" significaba abrir Instagram y buscarla a
     * mano. No siempre viene —el proveedor no lo garantiza para todas las
     * redes—, y por eso el botón de la app solo aparece cuando hay enlace, en
     * vez de llevar a una página muerta.
     */
    @Column(length = 500)
    private String externalUrl;

    /** Motivo del fallo para esta red en particular, cuando status = FAILED. */
    @Column(length = 500)
    private String errorMessage;
}

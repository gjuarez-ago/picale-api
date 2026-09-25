package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.PostStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostResponse {
    private UUID id;
    private String caption;

    /** La idea tal como la dicto la persona; ver {@code Post.brief}. Puede venir nula. */
    private String brief;
    /** El título común a todas las redes; ver {@code Post.titulo}. Puede venir nulo. */
    private String titulo;

    /** Si TikTok le pone música al carrusel; ver {@code Post.musicaAutomatica}. Nulo = apagado. */
    private Boolean musicaAutomatica;

    /** Todas las fotos, en orden. El video, cuando lo es, va solo aquí. */
    private List<String> mediaUrls;

    /**
     * La primera, repetida. Se manda además de la lista para no romper a las
     * apps instaladas que solo conocen este campo: siguen enseñando su
     * miniatura sin enterarse de que ahora puede haber seis.
     */
    private String mediaUrl;

    /**
     * El fotograma del video, para poder enseñarlo sin reproducirlo.
     *
     * <p>Lo saca {@code MiniaturaDeVideo} al confirmar la subida y vive en la
     * fila del archivo, no en la de la publicación. Se copia aquí porque la
     * lista de publicaciones no consulta {@code media_assets}: sin este campo
     * pintaba un icono gris de reproducir para todos los videos, y con dos
     * videos ya no se sabe cuál es cuál sin abrirlos.
     *
     * <p>{@code null} en las fotos —ahí la miniatura es la propia foto— y en
     * los videos cuyo fotograma aún no está o no se pudo sacar.
     */
    private String thumbnailUrl;

    private Integer videoDurationSeconds;

    /**
     * Que clase de publicacion es: PHOTO, REEL o STORY. Puede venir nulo en
     * publicaciones anteriores a los formatos.
     *
     * <p>La app lo necesita para corregir: abre el mismo flujo de crear con
     * el formato ya fijado, y sin este dato tendria que adivinarlo del
     * archivo, que es justo lo que no se puede con una historia.
     */
    private String format;
    private PostStatus status;
    private LocalDateTime scheduledAt;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;

    /** Cuando se archivo, o null si no lo esta. */
    private LocalDateTime archivedAt;
    private List<PostTargetResponse> targets;
}

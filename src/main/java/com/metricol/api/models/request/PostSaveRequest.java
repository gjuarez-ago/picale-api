package com.metricol.api.models.request;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PostSaveRequest {

    @NotBlank
    private String caption;

    /**
     * La idea tal como la dicto o escribio la persona, antes de que la IA la
     * convirtiera en textos por red. Opcional: los clientes que aun no la
     * mandan siguen funcionando, solo que al corregir partiran del texto que
     * salio en vez de lo dictado.
     */
    private String brief;

    /**
     * El título común a todas las redes (ver {@code Post.titulo}). Opcional:
     * sin él se saca del caption al publicar. Se recorta a 90.
     */
    private String titulo;

    /**
     * Que TikTok le ponga música de fondo al carrusel de fotos (ver
     * {@code Post.musicaAutomatica}). Opcional: nulo desde un cliente viejo
     * conserva lo que hubiera guardado. Solo cuenta en fotos que van a TikTok.
     */
    private Boolean musicaAutomatica;

    /**
     * Las fotos de la publicación, en el orden en que se verán, o un solo
     * video. El tope de fotos lo pone {@code app.media.max-images-per-post} y
     * lo comprueba el servidor: la app también lo respeta, pero una app vieja
     * o un script no tienen por qué.
     */
    private List<String> mediaUrls;

    /**
     * Un solo medio, como se pedía antes. Se sigue aceptando para que las
     * apps ya instaladas puedan publicar sin actualizarse; si vienen las dos
     * cosas, manda {@code mediaUrls}.
     */
    private String mediaUrl;

    /**
     * Duración del video en segundos, si el medio es video. Opcional: sin
     * ella no se bloquea nada y la red sigue siendo quien decide.
     */
    private Integer videoDurationSeconds;

    /**
     * Qué clase de publicación es: {@code PHOTO}, {@code REEL} o
     * {@code STORY}. Es lo que decide qué reglas se aplican y cómo sale en la
     * red.
     *
     * <p>Opcional a propósito. Una app o un panel que todavía no sepan de
     * formatos siguen publicando igual: sin él se deduce del archivo —video es
     * reel, lo demás carrusel—, que es lo que hacía toda la aplicación antes
     * de que esto existiera. Ver {@code Post.formatoEfectivo()}.
     *
     * <p>Lo que no se puede es deducir una historia, así que para publicar una
     * hay que pedirla por su nombre.
     */
    private String format;

    private LocalDateTime scheduledAt;

    private boolean publishNow;

    private List<UUID> socialAccountIds;

    /**
     * El texto de cada red, con la llave en mayusculas (INSTAGRAM, TIKTOK...).
     *
     * <p>Opcional: lo que falte usa {@link #caption}, que sigue siendo el
     * texto de la publicacion. Asi una app vieja —o el panel web, que todavia
     * no sabe de esto— siguen funcionando igual que antes.
     */
    private java.util.Map<String, String> captionsPorRed;
}

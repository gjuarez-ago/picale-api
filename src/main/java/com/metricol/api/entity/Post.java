package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.enums.PostStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    private String tenantId;

    @Column(length = 2000)
    private String caption;

    /**
     * Las fotos —o el video— de la publicación, en el orden en que se verán.
     *
     * <p>Una lista y no un solo campo porque un carrusel es UNA publicación
     * con varias imágenes: seis fotos son seis posiciones de la misma
     * publicación, no seis publicaciones. Antes esto era un {@code mediaUrl}
     * suelto y el carrusel no tenía dónde existir.
     *
     * <p>{@code EAGER} a propósito: la lista es de seis elementos como mucho y
     * se necesita siempre que se lee una publicación, incluido al armar la
     * respuesta fuera de una transacción. Perezosa habría costado una consulta
     * por publicación al pintar el calendario, o un fallo de sesión cerrada.
     *
     * <p>El video siempre va solo: lo impone {@code PostService} al guardar.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "post_media", joinColumns = @JoinColumn(name = "post_id"))
    // "sort_order" y no "position": POSITION es palabra reservada en varios
    // motores (H2 y MySQL la usan como función), y una columna con ese nombre
    // rompe el DDL en unos y no en otros — el peor tipo de fallo, el que solo
    // aparece en un entorno.
    @OrderColumn(name = "sort_order")
    @Column(name = "url", length = 1000)
    @Builder.Default
    private List<String> mediaUrls = new ArrayList<>();

    /**
     * Duración del video en segundos, cuando el medio es video y la app la
     * midió. Se guarda —en vez de solo usarla y descartarla— porque también
     * hace falta al publicar una programada: en ese momento el archivo ya no
     * está en el teléfono de nadie a quien preguntarle.
     *
     * <p>{@code null} en fotos, y también en videos subidos por un cliente
     * que no la manda; ver {@link com.metricol.api.config.VideoLimitsProperties}.
     */
    private Integer videoDurationSeconds;

    /**
     * Foto o video, resuelto al guardar a partir de la fila del archivo en
     * {@code media_assets} — que es quien lo sabe de verdad, porque lo dedujo
     * del content-type que se firmó al subir.
     *
     * <p>Se guarda en vez de deducirse cada vez a propósito. De esto depende a
     * qué endpoint del proveedor se manda la publicación, y la alternativa era
     * mirarle la extensión a la URL: una verdad que ya está en la base,
     * reconstruida adivinando. Una URL sin extensión, con parámetros detrás o
     * servida desde otro dominio acababa en el endpoint de fotos con un video
     * dentro.
     *
     * <p>{@code null} en las filas anteriores a esta columna; ahí
     * {@link #esVideo()} vuelve a la extensión, que es lo que las decidió
     * cuando se guardaron.
     */
    @Enumerated(EnumType.STRING)
    private MediaType mediaType;

    /**
     * Qué clase de publicación es: carrusel, reel, historia.
     *
     * <p>Es lo que {@link #mediaType} nunca pudo ser. El tipo de archivo dice
     * si hay un video; no dice si ese video es un reel o una historia, y eso
     * cambia lo que la red admite —una historia lleva un archivo y dura un
     * minuto— y cómo sale publicado. Sin este campo la respuesta era siempre
     * "reel", porque es lo que upload-post hace cuando no se le dice nada.
     *
     * <p>{@code null} en las filas anteriores a esta columna, y ahí
     * {@link #formatoEfectivo()} lo deduce del archivo, que es exactamente lo
     * que decidió cómo salieron cuando se publicaron. Aquí no se cambia de
     * opinión sobre algo que ya salió.
     */
    @Enumerated(EnumType.STRING)
    private PostFormat format;

    /**
     * El fotograma del video, copiado aquí para que sobreviva al archivo.
     *
     * <p>Vive también en {@code media_assets}, y esa era la única copia: al
     * borrar el video desde Contenido —que es lo que hay que hacer para
     * liberar espacio— la publicación ya publicada se quedaba sin portada para
     * siempre. Pasó en producción y así se encontró.
     *
     * <p>Copiarlo no es duplicar por duplicar: son dos cosas con vidas
     * distintas. El archivo es material reutilizable y se borra cuando estorba;
     * la publicación es historia y no debería cambiar de aspecto porque alguien
     * hiciera limpieza.
     *
     * <p>{@code null} mientras el fotograma no esté listo —se saca unos
     * segundos después de subir— y en las publicaciones anteriores a esta
     * columna. Ahí se vuelve a buscar en la fila del archivo, como antes.
     */
    @Column(length = 1000)
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    private PostStatus status;

    private LocalDateTime scheduledAt;

    private LocalDateTime publishedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Cuando alguien la archivo, o null si esta a la vista.
     *
     * <p>Archivar no borra ni cancela nada: una programada archivada sigue
     * saliendo a su hora. Solo la quita de las listas del dia a dia, que es
     * lo que se pide cuando el historial estorba.
     *
     * <p>Una fecha y no un booleano: cuesta lo mismo y deja saber desde
     * cuando, que es lo que hace falta el dia que alguien pregunte por que
     * una publicacion no aparece.
     */
    private LocalDateTime archivedAt;

    @Builder.Default
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PostTarget> targets = new ArrayList<>();

    /**
     * El primer medio, que es el que representa la publicación en una
     * miniatura. Existe para los clientes que solo saben de un medio: la
     * respuesta sigue trayendo {@code mediaUrl} además de la lista, y una app
     * sin actualizar sigue funcionando aunque solo vea la primera foto.
     */
    public String primerMedio() {
        return mediaUrls == null || mediaUrls.isEmpty() ? null : mediaUrls.get(0);
    }

    /**
     * ¿Lo que se publica es un video? Lo dice {@link #getMediaType()}, que se
     * resolvió al guardar contra la fila del archivo.
     *
     * <p>De aquí sale a qué endpoint del proveedor va la publicación, así que
     * equivocarse manda un video al endpoint de fotos y la red lo rechaza con
     * un error que no explica nada.
     */
    public boolean esVideo() {
        if (mediaType != null) {
            return mediaType == MediaType.VIDEO;
        }
        // Filas guardadas antes de que existiera la columna. Se vuelve a la
        // extensión, que es exactamente lo que decidió a qué endpoint fueron
        // estas publicaciones cuando se crearon: aquí no se cambia de opinión
        // sobre algo que ya salió.
        return esVideoPorExtension(primerMedio());
    }

    /**
     * El formato con el que hay que publicar esta entrada.
     *
     * <p>El elegido cuando lo hay. Cuando no —las filas anteriores a la
     * columna, y cualquier cliente que todavía no lo mande— se deduce del
     * archivo: un video es un reel y lo demás es carrusel. Es exactamente el
     * comportamiento que tenía la aplicación antes de que el formato se
     * pudiera elegir, así que una publicación vieja sigue saliendo como salió.
     *
     * <p>Nunca deduce {@link PostFormat#STORY}: una historia no se puede
     * adivinar mirando el archivo —el mismo video sirve para las dos cosas— y
     * suponerla convertiría una publicación normal en algo que se borra a las
     * veinticuatro horas.
     */
    public PostFormat formatoEfectivo() {
        if (format != null) {
            return format;
        }
        return esVideo() ? PostFormat.REEL : PostFormat.PHOTO;
    }

    /**
     * El respaldo: qué parece esta URL cuando no hay fila que lo diga.
     *
     * <p>Público porque {@code PostService} necesita este mismo criterio para
     * los medios que no son nuestros —una URL externa no tiene fila en
     * {@code media_assets}—, y dos copias del mismo criterio acaban
     * discrepando.
     */
    public static boolean esVideoPorExtension(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm");
    }
}

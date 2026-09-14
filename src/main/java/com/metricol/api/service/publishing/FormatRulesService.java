package com.metricol.api.service.publishing;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;

/**
 * Qué admite cada formato. La tabla, y solo esta.
 *
 * <p>Existe para que no haya dos respuestas a «¿cuántas fotos caben?» o «¿cuánto
 * puede durar?». La validación del servidor lee de aquí, y la pantalla también
 * —por {@code GET /publishing/formats}—, así que lo que se enseña y lo que se
 * exige no pueden separarse. Antes de esto los números vivían repartidos entre
 * {@code CaptureLimits} en la app, {@code EspecImagen} y las propiedades del
 * servidor, y ya no coincidían: la app cortaba los videos en 90 segundos
 * mientras el servidor creía admitir diez minutos en TikTok.
 *
 * <p><b>Las reglas son por FORMATO, no por red.</b> Es una decisión de producto
 * y no un descuido: un reel dura lo mismo en todas partes porque así se decidió,
 * no porque las redes coincidan —TikTok admite diez minutos—. Lo único que
 * cambia de red en red es <em>si</em> publica ese formato: TikTok no tiene
 * historias y YouTube no admite fotos.
 *
 * <p>Quedarse por debajo de lo que admite la red no cuesta nada; pasarse cuesta
 * la publicación. Por eso, donde hay duda, manda el número chico.
 */
@Service
public class FormatRulesService {

    /**
     * Cuántas fotos caben en un carrusel.
     *
     * <p>Seis, que es el número que la app ya aplicaba. Las redes admiten más
     * —diez Instagram, treinta y cinco TikTok— pero un carrusel largo no lo
     * termina de ver nadie, y este número es el que manda por ser el menor.
     *
     * <p>Configurable y en un solo sitio: cambiarlo mueve a la vez lo que
     * valida el servidor y lo que enseña la pantalla, porque las dos leen de
     * {@link #reglas()}.
     */
    @Value("${app.post.max-photos:6}")
    private int maxFotos = 6;

    /**
     * Lo que puede durar un video, en segundos.
     *
     * <p>Un minuto y medio para todas las redes y para los dos formatos de
     * video. No es el tope de ninguna red en concreto: es lo que esta
     * herramienta publica.
     */
    @Value("${app.post.max-video-seconds:90}")
    private int maxSegundosVideo = 90;

    /**
     * Lo mínimo que admite una red para dar un video por válido. Por debajo de
     * tres segundos lo rechazan todas, así que no es una decisión nuestra.
     */
    public static final int MIN_SEGUNDOS_VIDEO = 3;

    /** Una historia dura un día y no admite más de un minuto. Lo pone la red. */
    public static final int MAX_SEGUNDOS_HISTORIA = 60;

    /**
     * Lo que se puede hacer con un formato.
     *
     * @param redes         las que publican este formato. El resto se apagan
     *                      solas en la pantalla al elegirlo
     * @param maxArchivos   cuántos medios caben. Uno en todo lo que no sea
     *                      carrusel
     * @param minSegundos   {@code null} cuando el formato no lleva video
     * @param maxSegundos   {@code null} cuando el formato no lleva video
     * @param exigeVertical si hay que exigir 9:16. Es un error, no un aviso:
     *                      un reel apaisado lo rechaza Facebook y en el resto
     *                      sale con bandas, que es peor que no dejarlo subir
     */
    public record Regla(
            PostFormat formato,
            String label,
            boolean ofrecido,
            Set<Platform> redes,
            int maxArchivos,
            boolean admiteFoto,
            boolean admiteVideo,
            Integer minSegundos,
            Integer maxSegundos,
            boolean exigeVertical) {
    }

    /** La tabla, ya con los números configurados puestos. */
    public List<Regla> reglas() {
        return List.of(
                // Fotos. YouTube fuera: no publica imágenes, y ofrecerlo solo
                // llevaría a un rechazo con el carrusel ya subido.
                new Regla(PostFormat.PHOTO, PostFormat.PHOTO.getLabel(), true,
                        EnumSet.of(Platform.FACEBOOK, Platform.INSTAGRAM,
                                Platform.TIKTOK, Platform.LINKEDIN),
                        maxFotos, true, false, null, null, false),

                // Reel. Lo admiten las cinco. Vertical obligatorio: es lo que
                // pide el formato en todas, y Facebook lo rechaza si no.
                new Regla(PostFormat.REEL, PostFormat.REEL.getLabel(), true,
                        EnumSet.allOf(Platform.class),
                        1, false, true, MIN_SEGUNDOS_VIDEO, maxSegundosVideo, true),

                // Historia. Solo Meta: TikTok no las tiene y YouTube tampoco.
                // Es el único formato que acepta las dos clases de archivo.
                new Regla(PostFormat.STORY, PostFormat.STORY.getLabel(), true,
                        EnumSet.of(Platform.FACEBOOK, Platform.INSTAGRAM),
                        1, true, true, MIN_SEGUNDOS_VIDEO, MAX_SEGUNDOS_HISTORIA, true),

                // Video largo: previsto, no ofrecido. Sin vertical obligatorio
                // —un video de feed se ve bien apaisado— y sin tope propio
                // todavía, porque el tope es justo lo que hay que decidir
                // cuando se lance.
                new Regla(PostFormat.VIDEO, PostFormat.VIDEO.getLabel(), false,
                        EnumSet.of(Platform.FACEBOOK, Platform.INSTAGRAM,
                                Platform.TIKTOK, Platform.LINKEDIN, Platform.YOUTUBE),
                        1, false, true, MIN_SEGUNDOS_VIDEO, maxSegundosVideo, false));
    }

    /** Solo lo que se le puede proponer a alguien hoy. */
    public List<Regla> ofrecidas() {
        return reglas().stream().filter(Regla::ofrecido).toList();
    }

    /**
     * La regla de un formato. Nunca {@code null}: un formato que el enum
     * conoce siempre tiene entrada, y si algún día faltara es un fallo de
     * programación y no algo que deba fallar en tiempo de publicación.
     */
    public Regla de(PostFormat formato) {
        return reglas().stream()
                .filter(regla -> regla.formato() == formato)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Falta la regla del formato " + formato));
    }

    /** Si esa red publica ese formato. */
    public boolean admite(PostFormat formato, Platform red) {
        return de(formato).redes().contains(red);
    }
}

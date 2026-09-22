package com.metricol.api.service.ai;

import java.util.Collection;
import java.util.Comparator;

import com.metricol.api.enums.Platform;

/**
 * Cómo tiene que ser el texto en cada red.
 *
 * <p>Es el gemelo de {@code EspecImagen}, y existe por lo mismo: lo que sirve
 * en una red no sirve en otra, y la diferencia no es de gusto sino de límite
 * duro. TikTok corta en 90 caracteres; en LinkedIn caben 3000. Un texto
 * pensado para LinkedIn no entra en TikTok — lo corta a la mitad de una frase.
 *
 * <p>Por eso el texto se genera por red y no uno para todas. Y por eso
 * upload-post acepta {@code instagram_title}, {@code youtube_title} y compañía:
 * son ellos los primeros que saben que un texto único no funciona.
 *
 * <p>{@code estilo} no es decoración: es lo que se le dice al modelo para que
 * el mismo mensaje suene como pertenece a cada sitio. El mismo anuncio de una
 * promoción se escribe distinto en LinkedIn que en TikTok, y publicar el mismo
 * párrafo en las cinco es exactamente lo que delata una cuenta automatizada.
 */
public record EspecTexto(int maxCaracteres, int hashtagsSugeridos, String estilo) {

    /**
     * Cuanto admite el TITULO, que es un texto aparte del caption.
     *
     * <p>Desde el 22 sep 2026 viajan dos cosas por publicacion: un titulo
     * corto —el mismo para todas las redes— y el caption de cada red. En
     * upload-post el titulo va en {@code title}, {@code facebook_title},
     * {@code tiktok_title} (fotos), {@code linkedin_title} y
     * {@code youtube_title}; el caption va en el campo que cada red MUESTRA.
     *
     * <p>90 es el titulo de una publicacion de fotos en TikTok, el mas corto
     * de todos, y por eso es el tope comun: un solo titulo que cabe en TikTok
     * cabe en Facebook (255) y en YouTube (100), y asi se reutiliza en vez de
     * escribir tres.
     */
    public static final int TITULO_MAX = 90;

    /** La especificacion del titulo: corto, sin hashtags, profesional. */
    public static final EspecTexto TITULO = new EspecTexto(TITULO_MAX, 0,
            "un titulo profesional de una linea: nombra lo que se ofrece o el tema,"
                    + " sin hashtags, sin emojis y sin punto final");

    public static EspecTexto de(Platform platform) {
        return switch (platform) {
            case INSTAGRAM -> new EspecTexto(2200, 5,
                    "cercano y visual, con emojis con medida; los hashtags al final, nunca dentro de la frase");

            // 255: el caption de Facebook. Historicamente era el tope del
            // campo `title` de Facebook en upload-post (359 caracteres tiraron
            // una publicacion entera con un 400). Hoy el titulo va aparte
            // (TITULO) y el caption viaja por `description`, que admite mucho
            // mas, pero 255 se conserva como medida del caption a proposito:
            // es lo que Facebook enseña sin plegar en "Ver mas", y es el mismo
            // tope que TikTok, lo que permite reutilizar un texto entre ambas.
            case FACEBOOK -> new EspecTexto(255, 2,
                    "conversacional y directo, como quien le cuenta algo a un vecino; casi sin hashtags");

            // 255 y ya no 90: los 90 eran el TITULO de una publicacion de
            // fotos en TikTok, y ahi es donde iba nuestro texto. Ahora el
            // titulo va aparte (TITULO, 90) y el caption viaja por
            // `tiktok_description` en fotos y `tiktok_title` en video, que
            // admiten 4000 y 2200. Se queda en 255, igual que Facebook, para
            // que el mismo caption sirva en las dos redes.
            //
            // Cuenta como cuenta Java: un emoji fuera del BMP son dos unidades
            // UTF-16, que es la regla de TikTok ("an emoji counts as 2").
            case TIKTOK -> new EspecTexto(255, 2,
                    "corto y de un vistazo: el gancho en las primeras palabras y nada de"
                            + " relleno; lenguaje de la plataforma, sin sonar a anuncio");

            // La descripcion del video, no su titulo: el titulo va aparte
            // (TITULO, que cabe en los 100 de YouTube). En la descripcion caben
            // 5000; se pide menos porque nadie lee cinco mil caracteres bajo un
            // video, pero ya no se recorta a un titular.
            case YOUTUBE -> new EspecTexto(1000, 3,
                    "la descripcion del video: que se ve y por que verlo, en dos o tres"
                            + " frases claras, sin relleno ni saludo");

            case LINKEDIN -> new EspecTexto(3000, 3,
                    "profesional pero humano, en primera persona; nada de jerga corporativa vacia");
        };
    }

    /** El titulo recortado a lo que admite, o {@code null} si no hay. */
    public static String recortarTitulo(String titulo) {
        if (titulo == null || titulo.isBlank()) {
            return null;
        }
        // Un titulo es una linea: si viene con saltos, se queda la primera.
        String linea = titulo.strip().split("\\R", 2)[0].strip();
        return TITULO.recortar(linea);
    }

    /**
     * Un titulo sacado de un texto, para cuando nadie escribio uno.
     *
     * <p>Es el respaldo para clientes viejos y publicaciones anteriores al
     * titulo. No es el titulo profesional que escribe la IA: es la primera
     * frase del caption, sin hashtags, recortada a lo que cabe. Mejor eso que
     * un caption de 255 caracteres partido a los 90 con puntos suspensivos.
     */
    public static String tituloDesde(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String sinHashtags = texto.replaceAll("#\\S+", " ").replaceAll("\\s{2,}", " ").strip();
        String primera = sinHashtags.split("(?<=[.!?])\\s|\\R", 2)[0].strip();
        if (primera.isEmpty()) {
            primera = sinHashtags;
        }
        if (primera.endsWith(".")) {
            primera = primera.substring(0, primera.length() - 1);
        }
        return recortarTitulo(primera);
    }

    /**
     * La más estricta de varias redes: la que menos texto admite.
     *
     * <p>Es para el campo {@code title} que va UNA sola vez en el envío, sea
     * cual sea el número de redes. Cada red recibe además el suyo propio
     * ({@code facebook_title} y compañía) con todo el texto que le quepa, así
     * que recortar aquí al mínimo no le quita nada a nadie: solo asegura que
     * el campo común pase la validación de la más estrecha.
     *
     * <p>Sin esto, el texto se recortaba por red y el común se mandaba entero,
     * de modo que el recorte no servía de nada — que es exactamente como se
     * perdió la publicación del 11 de septiembre.
     *
     * <p>El gemelo de {@code EspecImagen.interseccion}, y por el mismo motivo:
     * lo que va una vez para todas tiene que caber en todas.
     */
    public static EspecTexto masEstricta(Collection<Platform> platforms) {
        return platforms.stream()
                .map(EspecTexto::de)
                .min(Comparator.comparingInt(EspecTexto::maxCaracteres))
                // Sin redes no hay a quién ajustarse. No debería pasar —no se
                // publica sin destinos— pero devolver algo es mejor que
                // reventar al construir el envío.
                .orElseGet(() -> de(Platform.FACEBOOK));
    }

    /**
     * Recorta sin partir una palabra por la mitad.
     *
     * <p>Es la red de seguridad: al modelo se le pide el límite en el prompt,
     * pero un modelo no cuenta caracteres de forma fiable. Sin esto, un texto
     * de 110 caracteres llegaría a YouTube y lo rechazaría — o peor, lo
     * cortaría él en mitad de una palabra.
     */
    public String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        if (limpio.length() <= maxCaracteres) {
            return limpio;
        }
        int hasta = maxCaracteres - 1;
        // Un emoji son dos unidades UTF-16 y el corte puede caer justo entre
        // ellas. Partirlo no deja medio emoji: deja un caracter invalido, que
        // es como se cuela una interrogacion en un rombo al final del texto.
        if (Character.isHighSurrogate(limpio.charAt(hasta - 1))) {
            hasta--;
        }

        String corte = limpio.substring(0, hasta);
        int ultimoEspacio = corte.lastIndexOf(' ');
        // El 60% evita el caso raro de un texto sin espacios, donde cortar por
        // el ultimo espacio dejaria dos palabras de lo que era un parrafo.
        if (ultimoEspacio > maxCaracteres * 0.6) {
            corte = corte.substring(0, ultimoEspacio);
        }
        return corte.stripTrailing() + "…";
    }
}

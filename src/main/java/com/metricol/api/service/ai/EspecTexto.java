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

    public static EspecTexto de(Platform platform) {
        return switch (platform) {
            case INSTAGRAM -> new EspecTexto(2200, 5,
                    "cercano y visual, con emojis con medida; los hashtags al final, nunca dentro de la frase");

            // 255 y no 2000: es lo que upload-post admite en el campo `title`
            // de Facebook, y lo rechaza entero si se pasa. Mismo aprendizaje
            // que TikTok y del mismo modo —un 400 del proveedor que no decia
            // nada hasta leerle el cuerpo—: "Facebook title is too long (359
            // characters). Maximum allowed is 255."
            //
            // No es el limite de un post de Facebook, que es mucho mayor: es
            // el del titulo, que es el campo por el que pasa nuestro texto.
            // Mientras lo mandemos por ahi, 255 es el numero que manda.
            case FACEBOOK -> new EspecTexto(255, 2,
                    "conversacional y directo, como quien le cuenta algo a un vecino; casi sin hashtags");

            // 90 y no 2200: lo que TikTok admite en el titulo de una
            // publicacion, y lo rechaza entero si se pasa. Lo aprendimos de un
            // rechazo real —115 caracteres— que se veia solo como un 400 del
            // proveedor. Cuenta como cuenta Java: un emoji fuera del BMP son
            // dos unidades UTF-16, que es exactamente la regla de TikTok
            // ("an emoji counts as 2"), asi que length() vale de medida.
            //
            // Se aplica tambien al video, donde quiza cabria mas: el texto va
            // al mismo campo `title`, y el coste de equivocarse no es simetrico
            // —quedarse corto se lee raro, pasarse no se publica—.
            //
            // Con 90 caracteres, cuatro hashtags se comen la mitad del texto:
            // el que fallo gastaba 51 de 115 en ellos. Por eso bajan a dos.
            case TIKTOK -> new EspecTexto(90, 2,
                    "muy corto, de un vistazo: el gancho en las primeras palabras y nada de"
                            + " relleno; lenguaje de la plataforma, sin sonar a anuncio");

            // 100 y no 5000: en la descripcion de un video de YouTube caben
            // 5000, pero nuestro texto no viaja por ahi — va en `title`, que
            // YouTube limita a 100 y ademas exige. Mismo aprendizaje que
            // Facebook con sus 255 y TikTok con sus 90: mientras lo mandemos
            // por el campo de titulo, el numero que manda es el del titulo.
            //
            // Dos hashtags y no cinco por lo mismo que en TikTok: en 100
            // caracteres, cinco se comen el texto entero.
            case YOUTUBE -> new EspecTexto(100, 2,
                    "un titulo, no un parrafo: lo que promete el video en las primeras"
                            + " palabras, sin relleno ni saludo");

            case LINKEDIN -> new EspecTexto(3000, 3,
                    "profesional pero humano, en primera persona; nada de jerga corporativa vacia");
        };
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

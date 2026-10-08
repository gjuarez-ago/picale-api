package com.metricol.api.service.imagenes;

import java.util.List;

import com.metricol.api.models.request.CampaignImageRequest;

/**
 * Traduce la ficha de la conversación a lo que el generador ya sabe recibir.
 *
 * <p>Es la bisagra del asistente: todo lo de antes es hablar, y de aquí en
 * adelante es el {@code CampaignImageService} de siempre, sin tocarle una
 * línea. Que esta clase sea pequeña y sin dependencias es a propósito — se
 * prueba entera sin levantar nada, y es donde se nota enseguida si la
 * conversación entendió mal.
 *
 * <p>Lo que NO se traduce también importa: el tono, el estilo visual y los
 * colores se dejan vacíos para que los ponga el perfil de la marca, que ya los
 * sabe. Mandarlos desde aquí sería repetir —y acabar contradiciendo— lo que el
 * negocio ya dijo de sí mismo una vez.
 */
public final class PeticionDesdeFicha {

    /** Tope del brief en {@link CampaignImageRequest}. */
    static final int MAX_BRIEF = 1500;

    /** Tope del objetivo. */
    static final int MAX_OBJETIVO = 80;

    private PeticionDesdeFicha() {
    }

    public static CampaignImageRequest armar(FichaDeImagen ficha) {
        if (ficha == null || !ficha.completa()) {
            throw new IllegalArgumentException("Todavía falta algo por contarme antes de crearla.");
        }
        return new CampaignImageRequest(
                null,
                new CampaignImageRequest.Format(formato(ficha.formato()), null, null),
                ficha.fotos().isEmpty() ? null : List.copyOf(ficha.fotos()),
                null,
                brief(ficha),
                recortar(ficha.queQuiereQuePase(), MAX_OBJETIVO),
                null,
                null,
                null,
                null,
                ficha.redes().isEmpty() ? null : List.copyOf(ficha.redes()));
    }

    /**
     * El encargo, en las palabras de la persona.
     *
     * <p>Se arma con frases sueltas y no con un párrafo bonito porque el
     * generador lo lee como instrucciones, no como prosa: una línea por cosa
     * es más difícil de malinterpretar que un texto corrido.
     */
    static String brief(FichaDeImagen f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.queSeAnuncia().strip());

        if (hay(f.cuando())) {
            sb.append("\nCuándo: ").append(f.cuando().strip());
        }
        if (hay(f.textoEnLaImagen())) {
            // Va con comillas y en línea aparte: es lo que de verdad tiene que
            // salir escrito, y confundirlo con una descripción es el error que
            // hace que el texto salga mal o no salga.
            sb.append("\nEl texto que debe salir DENTRO de la imagen, tal cual: \"")
                    .append(f.textoEnLaImagen().strip()).append('"');
        }
        if (hay(f.notas())) {
            sb.append("\nTambién: ").append(f.notas().strip());
        }
        if (hay(f.piezaElegida())) {
            // Afinar sobre la que gustó, no empezar de cero: es la diferencia
            // entre "acerca más los tacos" y una imagen distinta cada vez.
            sb.append("\nPartimos de la versión que ya eligió y la ajustamos; no empieces de cero.");
        }
        return recortar(sb.toString(), MAX_BRIEF);
    }

    /**
     * El formato, como lo nombra el generador.
     *
     * <p>Lo desconocido cae en {@code post}: es el formato más común y el que
     * sirve en todas las redes. Fallar aquí por un valor raro sería tirar una
     * conversación entera por una palabra.
     */
    static String formato(String deLaFicha) {
        if (deLaFicha == null) {
            return "post";
        }
        return switch (deLaFicha.strip().toUpperCase()) {
            case "HISTORIA", "STORY" -> "story";
            case "CARRUSEL", "CAROUSEL" -> "carousel";
            default -> "post";
        };
    }

    private static boolean hay(String s) {
        return s != null && !s.isBlank();
    }

    private static String recortar(String valor, int tope) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String v = valor.strip();
        return v.length() <= tope ? v : v.substring(0, tope);
    }
}

package com.metricol.api.enums;

import java.util.Optional;

/**
 * Cómo suena una marca. La persona elige hasta tres.
 *
 * <p>Enum y no texto libre, como {@link ObjetivoRedes}: son opciones fijas de una
 * pantalla con botones, y cada una se le explica a la IA con una frase que ya
 * sabemos que entiende. Un texto libre ("como pana pero elegante") daría tonos
 * imprevisibles y, peor, sería un sitio más por donde meter instrucciones al modelo.
 */
public enum TonoDeMarca {

    CERCANO("cercano y amable", "warm and friendly"),
    PROFESIONAL("profesional y serio", "professional and serious"),
    DIVERTIDO("divertido y con humor", "playful and funny"),
    ELEGANTE("elegante y sobrio", "elegant and refined"),
    CONFIABLE("confiable y claro", "trustworthy and clear"),
    JUVENIL("juvenil y fresco", "young and fresh"),
    INSPIRADOR("inspirador y motivador", "inspiring and motivating"),
    DIRECTO("directo y al grano", "direct and to the point");

    /** Cuántos se pueden elegir a la vez: cuatro tonos a la vez no son un tono, son un promedio. */
    public static final int MAXIMO = 3;

    private final String es;
    private final String en;

    TonoDeMarca(String es, String en) {
        this.es = es;
        this.en = en;
    }

    /** Como se le dice a la IA cuando escribe en español. */
    public String es() {
        return es;
    }

    /** Como se le dice a la IA cuando se le pide una imagen (esos prompts van en inglés). */
    public String en() {
        return en;
    }

    public static Optional<TonoDeMarca> de(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(codigo.strip().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}

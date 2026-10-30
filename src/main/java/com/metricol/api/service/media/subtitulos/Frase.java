package com.metricol.api.service.media.subtitulos;

import java.util.List;

/**
 * Un trozo de lo dicho, como se le enseña a la persona para corregirlo.
 *
 * <p>Se corrige por frases y no palabra por palabra: es como lo lee la gente,
 * y cambiar "sat" por "SAT" o juntar dos palabras no debe obligar a nadie a
 * tocar tiempos.
 *
 * @param palabras las palabras con sus tiempos; puede venir vacía si la
 *                 persona reescribió la frase, y entonces se reparten solas
 */
public record Frase(double inicio, double fin, String texto, List<Palabra> palabras) {
}

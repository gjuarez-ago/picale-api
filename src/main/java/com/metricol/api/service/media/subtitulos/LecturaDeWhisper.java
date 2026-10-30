package com.metricol.api.service.media.subtitulos;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lee la respuesta de Whisper ({@code verbose_json} con tiempos por palabra y
 * por segmento) y se queda solo con lo que de verdad se dijo.
 *
 * <p>Whisper inventa texto cuando no hay voz: con dos segundos de un tono puro
 * devolvió «Subtítulos realizados por la comunidad de Amara.org» (probado el
 * 30 oct 2026). Son frases de los subtítulos con que lo entrenaron, y en un
 * video quedarían pintadas encima de la música. Por eso se tira el segmento
 * que el propio modelo marca como "aquí no habla nadie", y el que es entero
 * una de esas frases conocidas.
 */
public final class LecturaDeWhisper {

    /** Encima de esto, el modelo cree que en ese tramo no habla nadie. */
    static final double SIN_VOZ = 0.6;

    /** Lo que Whisper escribe solo, sin que nadie lo diga. Normalizado: sin acentos ni signos. */
    private static final List<String> INVENTADAS = List.of(
            "subtitulos realizados por la comunidad de amara org",
            "subtitulos por la comunidad de amara org",
            "subtitulado por la comunidad de amara org",
            "gracias por ver",
            "gracias por ver el video",
            "gracias por su atencion",
            "suscribete",
            "suscribete al canal",
            "no olvides suscribirte",
            "musica");

    private LecturaDeWhisper() {
    }

    /** Las palabras dichas, en orden, con tiempos que nunca van hacia atrás. */
    public static List<Palabra> palabras(Map<String, Object> respuesta) {
        if (respuesta == null) {
            return List.of();
        }
        List<double[]> descartados = new ArrayList<>();
        if (respuesta.get("segments") instanceof List<?> segmentos) {
            for (Object s : segmentos) {
                if (s instanceof Map<?, ?> segmento && inventado(segmento)) {
                    descartados.add(new double[] { numero(segmento.get("start")), numero(segmento.get("end")) });
                }
            }
        }

        List<Palabra> palabras = new ArrayList<>();
        if (!(respuesta.get("words") instanceof List<?> crudas)) {
            return palabras;
        }
        double ultimo = 0;
        for (Object w : crudas) {
            if (!(w instanceof Map<?, ?> cruda)) continue;
            String texto = cruda.get("word") == null ? "" : String.valueOf(cruda.get("word")).strip();
            if (texto.isEmpty()) continue;
            double inicio = Math.max(numero(cruda.get("start")), ultimo);
            double fin = Math.max(numero(cruda.get("end")), inicio);
            double medio = (inicio + fin) / 2;
            if (descartados.stream().anyMatch(d -> medio >= d[0] && medio <= d[1])) continue;
            palabras.add(new Palabra(texto, inicio, fin));
            ultimo = inicio;
        }
        return palabras;
    }

    private static boolean inventado(Map<?, ?> segmento) {
        if (numero(segmento.get("no_speech_prob")) > SIN_VOZ) {
            return true;
        }
        Object texto = segmento.get("text");
        return texto != null && INVENTADAS.contains(normalizar(String.valueOf(texto)));
    }

    static String normalizar(String texto) {
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static double numero(Object valor) {
        return valor instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : 0;
    }
}

package com.metricol.api.service.agente.video;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.metricol.api.enums.PostFormat;

/**
 * Qué se hace con un video ya analizado: publicarlo (como Reel o historia),
 * recortarlo, o dejarlo en Observación. Y de qué segundo sale la portada.
 *
 * <p>Como {@code DecisorDelAgente} con las fotos: el {@link AnalistaDeVideo}
 * observa y esto decide, con reglas fijas, sin red ni base de datos, para que
 * cada decisión se pueda explicar y probar.
 *
 * <p>El árbol:
 * <ol>
 * <li>¿Calidad baja que no se arregla (movido, desenfocado)? Observación.</li>
 * <li>¿Formato? Algo del momento y corto va de historia (si hay dónde); lo
 * demás, Reel.</li>
 * <li>¿Más largo de lo que cabe? Si hay editor, se recorta al mejor tramo; si
 * no, Observación con el tramo exacto para recortarlo a mano.</li>
 * <li>Portada: el cuadro más claro y representativo que vio el Analista.</li>
 * </ol>
 */
public final class DecisorDeVideo {

    public enum Tratamiento { PUBLICAR, RECORTAR, OBSERVACION }

    /** Lo que dura como mucho un Reel en Pícale (app.post.max-video-seconds) y una historia. */
    public static final int MAX_REEL = 90;
    public static final int MAX_HISTORIA = 60;

    /**
     * @param formato   REEL o STORY; nulo si va a Observación
     * @param portadaMs de qué milisegundo sale la portada
     * @param inicio    el tramo a publicar: todo el video, o el que hay que recortar
     */
    public record Decision(Tratamiento tratamiento, PostFormat formato, int portadaMs, double inicio, double fin,
            List<String> pasos) {

        public String explicacion() {
            return String.join(" ", pasos);
        }
    }

    private static final Map<String, String> TIPOS = Map.of(
            "RECORRIDO", "un recorrido",
            "DEMOSTRACION", "una demostración",
            "TESTIMONIO", "un testimonio",
            "DETRAS_DE_CAMARAS", "un detrás de cámaras",
            "EVENTO", "un evento",
            "PROMOCION", "una promoción",
            "PRODUCTO", "un video de producto");

    private DecisorDeVideo() {
    }

    /**
     * @param segundos            lo que dura el video
     * @param hayRedesDeHistoria  si alguna red conectada publica historias (solo Meta)
     * @param editorPuedeRecortar si hay un {@link EditorDeVideo} que recorte
     */
    public static Decision decidir(AnalisisDeVideo a, double segundos, boolean hayRedesDeHistoria,
            boolean editorPuedeRecortar) {
        List<String> pasos = new ArrayList<>();
        String tipo = TIPOS.getOrDefault(a.tipo(), "un video");
        pasos.add("Lo vi completo" + (a.hayVoz() ? " y escuché lo que se dice" : "") + ": es " + tipo
                + " de " + duracion(segundos) + ".");

        // 1. Calidad.
        if (a.calidad() <= 2 && !a.arreglable()) {
            String falla = a.queFalla().isBlank() ? "tiene poca calidad" : "está " + a.queFalla();
            pasos.add("Pero " + falla + " y eso no se arregla: mejor grábalo otra vez.");
            return new Decision(Tratamiento.OBSERVACION, null, 0, 0, segundos, pasos);
        }

        // 2. Formato.
        double tramo = a.tramoFin() - a.tramoInicio();
        boolean cabeEnHistoria = segundos <= MAX_HISTORIA || (editorPuedeRecortar && tramo <= MAX_HISTORIA);
        PostFormat formato;
        if (a.efimero() && hayRedesDeHistoria && cabeEnHistoria) {
            formato = PostFormat.STORY;
            pasos.add("Es algo del momento: va de historia.");
        } else {
            formato = PostFormat.REEL;
            pasos.add(a.efimero() && !hayRedesDeHistoria
                    ? "Iría de historia, pero no tienes redes que las publiquen: va como Reel."
                    : "Va como Reel.");
        }

        // 3. Duración.
        int tope = formato == PostFormat.STORY ? MAX_HISTORIA : MAX_REEL;
        double inicio = 0;
        double fin = segundos;
        Tratamiento tratamiento = Tratamiento.PUBLICAR;
        if (segundos > tope) {
            inicio = a.tramoInicio();
            fin = Math.min(a.tramoFin(), inicio + tope);
            if (editorPuedeRecortar) {
                tratamiento = Tratamiento.RECORTAR;
                pasos.add("Dura más de " + tope + " s: lo recorto al mejor tramo, del " + reloj(inicio) + " al "
                        + reloj(fin) + ".");
            } else {
                pasos.add("Dura " + duracion(segundos) + " y " + (formato == PostFormat.STORY ? "una historia" : "un Reel")
                        + " es de hasta " + tope + " s. El mejor tramo es del " + reloj(inicio) + " al " + reloj(fin)
                        + ": recórtalo así y vuelve a subirlo (pronto lo recortaré yo).");
                return new Decision(Tratamiento.OBSERVACION, formato, 0, inicio, fin, pasos);
            }
        }

        // 4. Portada: dentro del tramo que se publica, contada desde su inicio.
        double portada = a.portadaSegundo();
        if (portada < inicio || portada > fin) {
            portada = inicio + Math.min(1, fin - inicio);
        }
        int portadaMs = (int) Math.round((portada - inicio) * 1000);
        if (formato == PostFormat.REEL) {
            pasos.add("De portada, el segundo " + Math.round(portada) + ": la toma más clara.");
        }
        pasos.add("Sin logo por ahora: en video llegará como marca de agua.");
        return new Decision(tratamiento, formato, portadaMs, inicio, fin, pasos);
    }

    /** «42 s» o «2 min 05 s». */
    public static String duracion(double segundos) {
        long s = Math.round(segundos);
        return s < 60 ? s + " s" : (s / 60) + " min " + String.format("%02d", s % 60) + " s";
    }

    /** «1:05». */
    static String reloj(double segundos) {
        long s = Math.round(segundos);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}

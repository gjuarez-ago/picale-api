package com.metricol.api.service.media.subtitulos;

/**
 * Un letrero informativo: el recuadro que aparece aparte de los subtítulos
 * para decir con pocas palabras lo que importa de ese momento, como en los
 * videos que explican algo en TikTok.
 *
 * @param texto  corto: se lee de un vistazo mientras la persona sigue hablando
 * @param inicio segundos del video ya recortado, igual que las palabras
 * @param altura dónde va el centro del letrero, de 0 (arriba) a 1 (abajo);
 *               nulo = arriba, donde no estorba a los subtítulos
 */
public record Letrero(Tipo tipo, String texto, double inicio, double fin, Double altura) {

    public enum Tipo {
        /** El gancho del principio: de qué va el video. */
        TITULO,
        /** La idea o el dato que hay que llevarse. */
        CLAVE,
        /** Una advertencia: lo que no hay que hacer. */
        AVISO,
        /** La invitación del final: guardar, comentar, escribir. */
        CIERRE
    }

    /** Lo más largo que se alcanza a leer sin dejar de oír. */
    public static final int MAX_LETRAS = 44;
}

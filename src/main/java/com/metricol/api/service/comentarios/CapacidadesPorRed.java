package com.metricol.api.service.comentarios;

import java.util.EnumSet;
import java.util.Set;

import com.metricol.api.enums.Platform;

/**
 * Qué deja hacer cada red con los comentarios.
 *
 * <p>Existe para que la pantalla no ofrezca lo que va a fallar. Un botón
 * "Ocultar" en LinkedIn solo puede acabar en un error que la persona no
 * entiende y que no puede arreglar; es mejor que el botón no esté.
 *
 * <p>Lo que dice cada valor sale de la documentación de upload-post
 * (https://docs.upload-post.com/api/comments/), revisada el 7 oct 2026. Si una
 * red cambia, se cambia aquí y las dos pantallas y el worker se enteran solos.
 */
public final class CapacidadesPorRed {

    private CapacidadesPorRed() {
    }

    /** Las redes de las que se traen comentarios. */
    private static final Set<Platform> SE_LEEN = EnumSet.of(
            Platform.INSTAGRAM, Platform.FACEBOOK, Platform.YOUTUBE, Platform.TIKTOK, Platform.LINKEDIN);

    /** Las redes donde se puede contestar. Hoy, las mismas. */
    private static final Set<Platform> SE_CONTESTA = EnumSet.of(
            Platform.INSTAGRAM, Platform.FACEBOOK, Platform.YOUTUBE, Platform.TIKTOK, Platform.LINKEDIN);

    /** Ocultar / mostrar. LinkedIn no tiene moderación por la API. */
    private static final Set<Platform> SE_OCULTA = EnumSet.of(
            Platform.INSTAGRAM, Platform.FACEBOOK, Platform.YOUTUBE, Platform.TIKTOK);

    /**
     * Redes que exigen volver a conectar la cuenta para poder leer y contestar
     * comentarios: TikTok no da el permiso con la conexión vieja, y YouTube
     * necesita el alcance {@code youtube.force-ssl}, que antes no se pedía.
     *
     * <p>No es un error de la persona ni algo que se pueda arreglar por
     * dentro: hay que pedírselo, una vez, con el aviso de reconexión que ya
     * existe.
     */
    private static final Set<Platform> PIDEN_RECONECTAR = EnumSet.of(Platform.TIKTOK, Platform.YOUTUBE);

    public static boolean seLeen(Platform red) {
        return red != null && SE_LEEN.contains(red);
    }

    public static boolean seContesta(Platform red) {
        return red != null && SE_CONTESTA.contains(red);
    }

    public static boolean seOculta(Platform red) {
        return red != null && SE_OCULTA.contains(red);
    }

    public static boolean pideReconectar(Platform red) {
        return red != null && PIDEN_RECONECTAR.contains(red);
    }

    /*
     * Dos cosas que NO hacen falta aquí, y conviene que se sepa por qué:
     *
     * - En Instagram solo se puede CONTESTAR a un comentario, no dejar uno
     *   nuevo. Nos da igual: nosotros siempre contestamos, y el cliente manda
     *   `comment_id` por eso mismo.
     * - Lo que escribimos en TikTok tarda unos 10 segundos en aparecer. No
     *   importa: la bandeja quita el comentario en cuanto se contesta, y la
     *   publicación no se vuelve a consultar hasta la próxima vuelta.
     */

    /** El nombre de la red tal como lo espera upload-post. */
    public static String comoLaLlamaUploadPost(Platform red) {
        return red == null ? null : red.name().toLowerCase();
    }
}

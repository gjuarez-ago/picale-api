package com.metricol.api.service.publishing;

import java.text.Normalizer;
import java.util.List;

import com.metricol.api.entity.Post;

/**
 * Decide sola si una publicación sale con música de fondo en TikTok.
 *
 * <p>Antes era un interruptor que había que acordarse de prender en cada
 * publicación, y nadie se acuerda: salían sin música casi todas, que en TikTok
 * es salir en silencio mientras el resto del feed suena.
 *
 * <p><b>Dónde aplica, y solo ahí:</b> carruseles de fotos que van a TikTok. Es
 * lo único que admite el proveedor ({@code auto_add_music}); en video el audio
 * es el del propio video, y escoger canción ({@code tiktok_music_id}) no está
 * disponible. Donde no aplica no se manda nada, ni apagado.
 *
 * <p><b>Quién manda.</b> Si la persona tocó el interruptor, manda ella: lo
 * automático es para quien no decidió, nunca para corregir a quien sí. Por eso
 * {@code null} (no decidió) y {@code false} (dijo que no) son cosas distintas y
 * se tratan distinto.
 *
 * <p><b>Cuándo se calla.</b> Hay publicaciones a las que una canción alegre les
 * sienta como una patada: un luto, una disculpa, un cierre por emergencia. El
 * daño no es simétrico —una foto de tacos sin música no ofende a nadie; un
 * aviso de fallecimiento con música de fondo sí— así que ante la duda se calla.
 * No se le pregunta a la IA a propósito: costaría créditos en cada publicación
 * y tardaría, y estas palabras no son ambiguas.
 */
public final class MusicaDeTikTok {

    private MusicaDeTikTok() {
    }

    /**
     * Palabras que desaconsejan la música. Van sin acentos y en minúsculas
     * porque el texto se normaliza antes de buscarlas: así "pésame" y "pesame"
     * son lo mismo, que es como la gente escribe de verdad en el teléfono.
     */
    private static final List<String> NO_PEGA_CON_MUSICA = List.of(
            // Luto.
            "luto", "pesame", "fallecim", "falleci", "descanse en paz", "q.e.p.d", "qepd",
            "condolencia", "lamentamos", "lamentablemente", "sentido fallecimiento", "funeral",
            "velorio", "sepelio", "memoria de",
            // Disculpas y problemas.
            "disculpa", "disculpen", "una disculpa", "pedimos perdon", "perdon por",
            "lamentamos informar", "problema", "falla", "se nos fue", "error nuestro",
            // Cierres y emergencias.
            "cerrado", "cerramos", "no abriremos", "sin servicio", "suspendemos",
            "suspendido", "cancelado", "cancelamos", "emergencia", "accidente",
            "incendio", "inundacion", "sismo", "temblor", "huracan", "alerta",
            "robo", "asalto", "nos robaron",
            // Avisos serios.
            "aviso importante", "comunicado", "se busca", "desaparecid", "ayuda urgente");

    /**
     * ¿Sale con música?
     *
     * @param post      la publicación, con lo que la persona haya decidido
     * @param redes     a dónde va, como lo nombra upload-post ("tiktok", …)
     * @param esVideo   si lo que se manda es un video
     */
    public static boolean decidir(Post post, List<String> redes, boolean esVideo) {
        if (post == null) {
            return false;
        }
        // Quien decidió, decidió. Incluso para decir que no.
        if (post.getMusicaAutomatica() != null) {
            return post.getMusicaAutomatica();
        }
        return aplicaSola(redes, esVideo, post.getCaption(), post.getTitulo());
    }

    /** La decisión automática, separada para poder probarla sin armar un Post. */
    static boolean aplicaSola(List<String> redes, boolean esVideo, String caption, String titulo) {
        if (esVideo || redes == null || !redes.contains("tiktok")) {
            return false;
        }
        return pegaConMusica(caption) && pegaConMusica(titulo);
    }

    /**
     * ¿Este texto admite una canción detrás?
     *
     * <p>Un texto vacío sí: una publicación sin palabras es casi siempre puro
     * producto, y ahí la música suma. Lo que descarta es nombrar una desgracia.
     */
    static boolean pegaConMusica(String texto) {
        if (texto == null || texto.isBlank()) {
            return true;
        }
        String limpio = sinAcentos(texto.toLowerCase());
        for (String palabra : NO_PEGA_CON_MUSICA) {
            if (limpio.contains(palabra)) {
                return false;
            }
        }
        return true;
    }

    /** "Pésame" y "pesame" tienen que ser la misma palabra: la gente escribe las dos. */
    private static String sinAcentos(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}

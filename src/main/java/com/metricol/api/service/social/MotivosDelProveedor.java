package com.metricol.api.service.social;

import java.util.Locale;

/**
 * Lo que upload-post dice cuando una red rechaza algo, dicho para la persona
 * que lo va a leer en la app.
 *
 * <p>Los mensajes del proveedor vienen en inglés, con su correo de soporte y
 * un enlace a su documentación. Nada de eso le sirve a quien está mirando su
 * publicación fallida en el teléfono; lo que le sirve es saber qué pasó y qué
 * hacer. Aquí se traducen los motivos que se conocen, y para los demás se
 * deja el mensaje original sin la publicidad.
 *
 * <p>Se mira primero el código, que es estable, y después el texto, porque no
 * todas las respuestas traen código.
 */
final class MotivosDelProveedor {

    private MotivosDelProveedor() {
    }

    static String traducir(String codigo, String mensaje, String etapa) {
        String c = minusculas(codigo);
        String m = minusculas(mensaje);

        if (c.contains("invalid_photo") || (m.contains("could not decode") && m.contains("photo"))
                || m.contains("invalid image file")) {
            return "La red no pudo leer la imagen: el archivo esta danado o incompleto.";
        }
        if (c.contains("invalid_video") || (m.contains("could not decode") && m.contains("video"))
                || m.contains("invalid video file")) {
            return "La red no pudo leer el video: el archivo esta danado o incompleto.";
        }
        if (c.contains("token") || c.contains("unauthorized") || c.contains("reauth")
                || m.contains("token") && (m.contains("expired") || m.contains("invalid"))
                || m.contains("reauthenticate") || m.contains("re-authenticate")) {
            return "La conexion con la red caduco. Vuelve a conectarla desde Redes.";
        }
        if (c.contains("rate_limit") || c.contains("quota") || m.contains("rate limit")
                || m.contains("too many requests") || m.contains("quota")) {
            return "La red alcanzo su limite de publicaciones por ahora. Intentalo mas tarde.";
        }

        String limpio = sinPublicidad(mensaje);
        if (limpio != null) {
            return limpio;
        }
        if (etapa != null && !etapa.isBlank()) {
            return "La red rechazo la publicacion (" + etapa + ").";
        }
        return "La red rechazo la publicacion.";
    }

    /**
     * El mensaje original sin las frases de soporte del proveedor.
     *
     * <p>"If this issue persists, please contact support at ..." y "Learn
     * more: https://..." son para quien integra la API, no para quien publica.
     */
    private static String sinPublicidad(String mensaje) {
        if (mensaje == null || mensaje.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String frase : mensaje.split("(?<=[.!?])\\s+")) {
            String f = frase.strip();
            String fl = f.toLowerCase(Locale.ROOT);
            if (fl.startsWith("if this issue persists") || fl.startsWith("learn more")
                    || fl.contains("contact support")) {
                continue;
            }
            if (!f.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(f);
            }
        }
        String limpio = sb.toString().strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static String minusculas(String texto) {
        return texto == null ? "" : texto.toLowerCase(Locale.ROOT);
    }
}

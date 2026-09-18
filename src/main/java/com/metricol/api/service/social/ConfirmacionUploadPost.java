package com.metricol.api.service.social;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.enums.Platform;
import com.metricol.api.service.publishing.ConfirmacionDelProveedor;
import com.metricol.api.service.publishing.ResultadoDeRed;

/**
 * Le pregunta a upload-post cómo acabó de verdad un envío.
 *
 * <p>Hace falta porque el proveedor publica en diferido. {@code /upload} acusa
 * recibo y contesta; las redes terminan después, cada una a su ritmo. Lo que
 * se sabe al colgar esa llamada no es el resultado, es el acuse.
 *
 * <p><b>Cómo se lee cada fila, y por qué así.</b> Mientras el envío sigue
 * abierto, {@code /uploadposts/status} lista TODAS las redes, también las que
 * aún no terminaron, y esas vienen con {@code success: false} y un
 * {@code message} tipo "Queued" o "Publishing". Eso no es un rechazo: es
 * "todavía no". La primera versión de este lector lo tomó por rechazo, y un
 * reel que TikTok publicó 58 segundos después de aceptarse quedó en la app
 * como fallido. Ahora una fila solo cuenta como rechazo con una prueba
 * explícita —un {@code error_message}, un {@code failure_stage}, un aviso de
 * omisión— o cuando el envío entero ya está cerrado y la red sigue en false.
 * Sin eso, la red se queda fuera del resultado, que es la forma de decir "no
 * se sabe" para que quien llama vuelva a preguntar.
 *
 * <p>Nunca lanza. Un fallo consultando no puede tumbar una publicación que
 * quizá ya salió: se devuelve "no se sabe" y quien llama decide.
 */
@Service
public class ConfirmacionUploadPost {

    private static final Logger log = LoggerFactory.getLogger(ConfirmacionUploadPost.class);

    private final UploadPostClient client;

    public ConfirmacionUploadPost(UploadPostClient client) {
        this.client = client;
    }

    /** Por identificador del envío: la vía exacta. */
    public ConfirmacionDelProveedor porEnvio(String requestId) {
        try {
            Map<String, Object> respuesta = client.status(requestId);
            if (respuesta == null) {
                return ConfirmacionDelProveedor.nada();
            }

            String estado = minusculas(texto(respuesta.get("status")));
            if ("not_found".equals(estado)) {
                log.warn("upload-post no reconoce el envio {}", requestId);
                return ConfirmacionDelProveedor.desconocida();
            }

            // "completed" y "failed" son los dos finales. Lo demás —pending,
            // queued, processing, in_progress— es que sigue trabajando.
            boolean terminado = "completed".equals(estado) || "failed".equals(estado)
                    || iguales(respuesta.get("completed"), respuesta.get("total"));

            return new ConfirmacionDelProveedor(terminado, false,
                    leerFilas(filas(respuesta.get("results")), Filtro.NINGUNO, terminado));
        } catch (Exception ex) {
            log.warn("No se pudo consultar el envio {} en upload-post: {}", requestId, ex.getMessage());
            return ConfirmacionDelProveedor.nada();
        }
    }

    /**
     * Por historial, acotado a un envío concreto.
     *
     * <p>Las filas del historial traen el {@code request_id} del envío que las
     * produjo, así que se pueden pedir exactas. Y todas son finales: el
     * historial solo guarda lo que ya pasó, por eso una fila con
     * {@code success: false} aquí sí es un rechazo.
     */
    public ConfirmacionDelProveedor porHistorial(String requestId) {
        return historial(new Filtro(requestId, null, null));
    }

    /**
     * Por historial, acotado a perfil y hora: el plan B cuando no hay
     * identificador que preguntar.
     *
     * <p>Se acota por los dos porque el historial es de la llave entera, que
     * es de todos los negocios, y solo trae las diez últimas subidas. Sin
     * hora no se acepta: sin ella se tomaría la publicación anterior del
     * mismo negocio como si fuera esta.
     */
    public ConfirmacionDelProveedor porHistorial(String profile, LocalDateTime desde) {
        if (profile == null || desde == null) {
            return ConfirmacionDelProveedor.nada();
        }
        return historial(new Filtro(null, profile, desde.toInstant(ZoneOffset.UTC)));
    }

    private ConfirmacionDelProveedor historial(Filtro filtro) {
        try {
            Map<String, Object> respuesta = client.historial();
            if (respuesta == null) {
                return ConfirmacionDelProveedor.nada();
            }
            // Nunca "terminado": el historial dice qué ya pasó, no qué falta.
            return new ConfirmacionDelProveedor(false, false,
                    leerFilas(filas(respuesta.get("history")), filtro, true));
        } catch (Exception ex) {
            log.warn("No se pudo leer el historial de upload-post: {}", ex.getMessage());
            return ConfirmacionDelProveedor.nada();
        }
    }

    // ---------- Lectura de las filas ----------

    /** Qué filas del proveedor pertenecen al envío que interesa. */
    private record Filtro(String requestId, String profile, Instant desde) {

        static final Filtro NINGUNO = new Filtro(null, null, null);

        boolean admite(Map<String, Object> fila, ConfirmacionUploadPost lector) {
            if (requestId != null && !requestId.equals(lector.texto(fila.get("request_id")))) {
                return false;
            }
            if (profile != null && !profile.equals(lector.texto(fila.get("profile_username")))) {
                return false;
            }
            if (desde != null) {
                Instant cuando = lector.aInstante(fila.get("upload_timestamp"));
                // Sin fecha legible no se puede saber si es de este envío, y
                // adivinar es lo que llevaba a dar por buena la de otro.
                if (cuando == null || cuando.isBefore(desde)) {
                    return false;
                }
            }
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filas(Object valor) {
        return valor instanceof List<?> lista
                ? (List<Map<String, Object>>) lista.stream()
                        .filter(Map.class::isInstance)
                        .map(f -> (Map<String, Object>) f)
                        .toList()
                : List.of();
    }

    /**
     * Convierte las filas del proveedor en un resultado por red.
     *
     * @param finales si las filas son definitivas. Con el envío cerrado, o en
     *                el historial, una red en {@code success: false} sin más
     *                explicación es un rechazo. Con el envío abierto, es una
     *                red que aún no terminó y se deja fuera.
     */
    private Map<Platform, ResultadoDeRed> leerFilas(
            List<Map<String, Object>> filas, Filtro filtro, boolean finales) {

        Map<Platform, ResultadoDeRed> porRed = new EnumMap<>(Platform.class);

        for (Map<String, Object> fila : filas) {
            if (!filtro.admite(fila, this)) {
                continue;
            }
            Platform red = red(texto(fila.get("platform")));
            // Se queda con la PRIMERA de cada red: las dos respuestas vienen
            // de lo más nuevo a lo más viejo.
            if (red == null || porRed.containsKey(red)) {
                continue;
            }

            // El proveedor no tenía esa red lista para este perfil. Es final
            // desde el primer momento: no va a intentarlo.
            if (Boolean.TRUE.equals(fila.get("skipped"))) {
                porRed.put(red, ResultadoDeRed.rechazada(primero(
                        texto(fila.get("skip_reason")),
                        texto(fila.get("message")),
                        "El proveedor no tenia esta red configurada.")));
                continue;
            }

            String postId = texto(fila.get("platform_post_id"));
            String url = texto(fila.get("post_url"));
            String mensaje = texto(fila.get("message"));

            if (Boolean.TRUE.equals(fila.get("success"))) {
                if (Boolean.TRUE.equals(fila.get("fallback_to_inbox"))) {
                    log.info("{}: el video quedo en la bandeja de borradores de TikTok, no en el perfil", red);
                }
                // Salió, pero mientras el envío siga abierto puede que el
                // enlace aún no esté escrito. Se espera a tenerlo: publicada
                // sin enlace es justo lo que se está corrigiendo.
                if (postId != null || url != null || finales) {
                    porRed.put(red, ResultadoDeRed.publicada(postId, url));
                }
                continue;
            }

            // success == false. Solo es rechazo con una prueba: un error
            // escrito, una etapa de fallo, un mensaje que lo diga, o el envío
            // ya cerrado. "Queued" y "Publishing" son "todavía no".
            String error = primero(texto(fila.get("error_message")), texto(fila.get("error")));
            String etapa = texto(fila.get("failure_stage"));
            if (error != null || etapa != null || finales || pareceFallo(mensaje)) {
                // Dicho para quien lo lee en la app, no como lo dice el
                // proveedor: ver MotivosDelProveedor.
                porRed.put(red, ResultadoDeRed.rechazada(MotivosDelProveedor.traducir(
                        texto(fila.get("error_code")), primero(error, mensaje), etapa)));
            }
        }

        return porRed;
    }

    private boolean pareceFallo(String mensaje) {
        if (mensaje == null) {
            return false;
        }
        String m = mensaje.toLowerCase(Locale.ROOT);
        return m.contains("fail") || m.contains("error") || m.contains("reject")
                || m.contains("denied") || m.contains("rechaz");
    }

    private Platform red(String nombre) {
        if (nombre == null) {
            return null;
        }
        try {
            return Platform.valueOf(nombre.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // Una red que el proveedor publica y nosotros no ofrecemos.
            return null;
        }
    }

    /**
     * La hora de una fila.
     *
     * <p>Viene de dos formas segun la llamada: texto ISO en el historial y
     * {@code {"$date": "..."}} en el estado. Las dos son el mismo instante.
     */
    @SuppressWarnings("unchecked")
    private Instant aInstante(Object valor) {
        if (valor instanceof Map<?, ?> envuelto) {
            return aInstante(((Map<String, Object>) envuelto).get("$date"));
        }
        String texto = texto(valor);
        if (texto == null) {
            return null;
        }
        try {
            return Instant.parse(texto);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private boolean iguales(Object uno, Object otro) {
        return uno instanceof Number a && otro instanceof Number b
                && a.longValue() == b.longValue() && b.longValue() > 0;
    }

    private String primero(String... candidatos) {
        for (String c : candidatos) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private String minusculas(String texto) {
        return texto == null ? "" : texto.toLowerCase(Locale.ROOT);
    }

    private String texto(Object valor) {
        if (valor == null) {
            return null;
        }
        String texto = valor.toString();
        return texto.isBlank() ? null : texto;
    }
}

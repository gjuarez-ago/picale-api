package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;
import com.metricol.api.service.publishing.ConfirmacionDelProveedor;
import com.metricol.api.service.publishing.ResultadoDeRed;

/**
 * Leer de upload-post cómo acabó de verdad una publicación.
 *
 * <p>Existe por dos fallos que salieron en producción, uno detrás del otro.
 * El primero: el código leía el acuse de {@code /upload} buscando un detalle
 * por red que ahí no existe y, al no encontrarlo, daba la red por publicada.
 * El segundo, en el arreglo del primero: mientras el envío sigue abierto el
 * proveedor lista las redes que aún no terminaron con {@code success: false}
 * y un mensaje "Publishing", y el lector lo tomó por rechazo. Un reel que
 * TikTok publicó 58 segundos después de aceptarse quedó en la app como
 * fallido, con un botón de corregir que lo habría publicado dos veces.
 *
 * <p>La regla que fija todo esto: <b>sin prueba no hay rechazo</b>. Una red
 * solo sale como fallida con un error escrito, una etapa de fallo, un aviso
 * de omisión, o el envío entero cerrado. Lo demás es "todavía no", y "todavía
 * no" se representa dejándola fuera del mapa.
 *
 * <p>Las respuestas de aquí abajo están copiadas de las de verdad.
 */
class ConfirmacionUploadPostTest {

    private final UploadPostClient client = mock(UploadPostClient.class);
    private final ConfirmacionUploadPost confirmacion = new ConfirmacionUploadPost(client);

    private static final String PERFIL = "2c24024a-c9dc-4309-a802-ed2fcdd4cb4f";
    private static final String ENVIO = "6006fedc4b854db99008f8668c88c897";

    /** Una fila del proveedor. Los nulos se omiten, como hace el JSON real. */
    private Map<String, Object> fila(String red, Boolean ok, Map<String, Object> extra) {
        Map<String, Object> f = new HashMap<>();
        f.put("platform", red);
        if (ok != null) {
            f.put("success", ok);
        }
        f.put("profile_username", PERFIL);
        f.put("request_id", ENVIO);
        f.put("upload_timestamp", "2026-09-17T19:21:35.936Z");
        f.putAll(extra);
        return f;
    }

    private Map<String, Object> publicada(String red, String id, String url) {
        return fila(red, true, Map.of("platform_post_id", id, "post_url", url));
    }

    private Map<String, Object> estado(String estado, int completadas, int total, List<Map<String, Object>> filas) {
        return Map.of("status", estado, "completed", completadas, "total", total, "results", filas);
    }

    // ---------- Lo que destapó el caso de TikTok ----------

    @Test
    void unaRedEnCursoNoEsUnRechazo() {
        // El envío del reporte, tal como lo habría visto el worker a los 20
        // segundos: tres redes cerradas y TikTok todavía publicando.
        when(client.status(ENVIO)).thenReturn(estado("in_progress", 3, 4, List.of(
                publicada("linkedin", "urn:li:share:7506432178583584769",
                        "https://www.linkedin.com/feed/update/urn:li:share:7506432178583584769/"),
                publicada("facebook", "1238494536023799_122113834929437166",
                        "https://www.facebook.com/1238494536023799_122113834929437166"),
                publicada("instagram", "18114822980068247", "https://www.instagram.com/p/DdZmYyqDNrc/"),
                fila("tiktok", false, Map.of("message", "Publishing")))));

        ConfirmacionDelProveedor resultado = confirmacion.porEnvio(ENVIO);

        assertThat(resultado.terminado()).isFalse();
        assertThat(resultado.porRed()).containsOnlyKeys(
                Platform.LINKEDIN, Platform.FACEBOOK, Platform.INSTAGRAM);
        // TikTok no está ni como publicada ni como fallida: no se sabe.
        assertThat(resultado.porRed()).doesNotContainKey(Platform.TIKTOK);
    }

    @Test
    void unaRedEnColaTampoco() {
        when(client.status(ENVIO)).thenReturn(estado("queued", 0, 1, List.of(
                fila("tiktok", false, Map.of("message", "Queued")))));

        assertThat(confirmacion.porEnvio(ENVIO).porRed()).isEmpty();
    }

    @Test
    void conElEnvioCerradoSalenLasCuatroConSuEnlace() {
        // La misma publicación, un minuto después.
        when(client.status(ENVIO)).thenReturn(estado("completed", 4, 4, List.of(
                publicada("linkedin", "urn:li:share:7506432178583584769", "https://li/1"),
                publicada("facebook", "1238494536023799_122113834929437166", "https://fb/1"),
                publicada("instagram", "18114822980068247", "https://ig/1"),
                publicada("tiktok", "7686586526137945362", "https://www.tiktok.com/t/7686586526137945362"))));

        ConfirmacionDelProveedor resultado = confirmacion.porEnvio(ENVIO);

        assertThat(resultado.terminado()).isTrue();
        assertThat(resultado.porRed()).hasSize(4);
        ResultadoDeRed tiktok = resultado.porRed().get(Platform.TIKTOK);
        assertThat(tiktok.publicada()).isTrue();
        assertThat(tiktok.postId()).isEqualTo("7686586526137945362");
        assertThat(tiktok.url()).isEqualTo("https://www.tiktok.com/t/7686586526137945362");
    }

    // ---------- Qué SÍ cuenta como rechazo ----------

    @Test
    void unErrorEscritoEsRechazoAunqueElEnvioSigaAbierto() {
        when(client.status(ENVIO)).thenReturn(estado("in_progress", 1, 2, List.of(
                fila("tiktok", false, Map.of("error_message", "Video is too long for this account")),
                fila("facebook", false, Map.of("message", "Publishing")))));

        Map<Platform, ResultadoDeRed> porRed = confirmacion.porEnvio(ENVIO).porRed();

        assertThat(porRed).containsOnlyKeys(Platform.TIKTOK);
        assertThat(porRed.get(Platform.TIKTOK).publicada()).isFalse();
        assertThat(porRed.get(Platform.TIKTOK).error()).isEqualTo("Video is too long for this account");
    }

    @Test
    void unaEtapaDeFalloTambien() {
        when(client.status(ENVIO)).thenReturn(estado("in_progress", 1, 2, List.of(
                fila("instagram", false, Map.of("failure_stage", "media_container")))));

        // Sin texto del proveedor, la etapa es lo unico que hay para decir.
        assertThat(confirmacion.porEnvio(ENVIO).porRed().get(Platform.INSTAGRAM).error())
                .isEqualTo("La red rechazo la publicacion (media_container).");
    }

    @Test
    void unMensajeQueDiceFalloTambien() {
        when(client.status(ENVIO)).thenReturn(estado("in_progress", 1, 2, List.of(
                fila("youtube", false, Map.of("message", "Failed: quota exceeded")))));

        // Y se traduce: "quota" es un motivo conocido.
        assertThat(confirmacion.porEnvio(ENVIO).porRed().get(Platform.YOUTUBE).error())
                .isEqualTo("La red alcanzo su limite de publicaciones por ahora. Intentalo mas tarde.");
    }

    @Test
    void unaRedOmitidaPorElProveedorEsRechazoDesdeElPrimerMomento() {
        when(client.status(ENVIO)).thenReturn(estado("in_progress", 0, 1, List.of(
                fila("linkedin", false, Map.of("skipped", true, "skip_reason", "LinkedIn not connected")))));

        ResultadoDeRed linkedin = confirmacion.porEnvio(ENVIO).porRed().get(Platform.LINKEDIN);

        assertThat(linkedin.publicada()).isFalse();
        assertThat(linkedin.error()).isEqualTo("LinkedIn not connected");
    }

    @Test
    void conElEnvioCerradoUnFalseSinExplicacionSiEsRechazo() {
        // Ya no va a cambiar: ahí sí se cierra, con lo que haya de mensaje.
        when(client.status(ENVIO)).thenReturn(estado("completed", 2, 2, List.of(
                publicada("facebook", "1", "https://fb/1"),
                fila("tiktok", false, Map.of()))));

        ResultadoDeRed tiktok = confirmacion.porEnvio(ENVIO).porRed().get(Platform.TIKTOK);

        assertThat(tiktok.publicada()).isFalse();
        assertThat(tiktok.error()).isEqualTo("La red rechazo la publicacion.");
    }

    // ---------- Publicada sin enlace ----------

    @Test
    void publicadaSinEnlaceConElEnvioAbiertoSeEsperaAlEnlace() {
        // El proveedor ya dice que salió pero aún no escribió el enlace. Se
        // espera: "publicada sin enlace" es justo lo que se está corrigiendo.
        when(client.status(ENVIO)).thenReturn(estado("in_progress", 1, 2, List.of(
                fila("facebook", true, Map.of("message", "Published")))));

        assertThat(confirmacion.porEnvio(ENVIO).porRed()).isEmpty();
    }

    @Test
    void publicadaSinEnlaceConElEnvioCerradoSeAceptaSinEnlace() {
        // Si ya cerró y sigue sin enlace, no va a llegar. Mejor publicada sin
        // enlace que abierta para siempre.
        when(client.status(ENVIO)).thenReturn(estado("completed", 1, 1, List.of(
                fila("facebook", true, Map.of()))));

        ResultadoDeRed facebook = confirmacion.porEnvio(ENVIO).porRed().get(Platform.FACEBOOK);
        assertThat(facebook.publicada()).isTrue();
        assertThat(facebook.url()).isNull();
    }

    // ---------- El envío en sí ----------

    @Test
    void siElProveedorNoReconoceElEnvioLoDice() {
        when(client.status("no-existe")).thenReturn(Map.of("status", "not_found", "results", List.of()));

        ConfirmacionDelProveedor resultado = confirmacion.porEnvio("no-existe");

        assertThat(resultado.sinRastro()).isTrue();
        assertThat(resultado.terminado()).isFalse();
        assertThat(resultado.porRed()).isEmpty();
    }

    @Test
    void unEnvioFallidoEnteroTambienEstaTerminado() {
        when(client.status(ENVIO)).thenReturn(estado("failed", 1, 1, List.of(
                fila("tiktok", false, Map.of("error_message", "Token expired")))));

        assertThat(confirmacion.porEnvio(ENVIO).terminado()).isTrue();
    }

    @Test
    void unaRespuestaQueNoSeEntiendeNoEsUnExito() {
        // El acuse de `/upload`, que no trae resultados por red. Antes valía
        // como publicado en todas.
        when(client.status(ENVIO)).thenReturn(Map.of("success", true, "request_id", ENVIO));

        ConfirmacionDelProveedor resultado = confirmacion.porEnvio(ENVIO);

        assertThat(resultado.terminado()).isFalse();
        assertThat(resultado.porRed()).isEmpty();
    }

    @Test
    void siLaConsultaFallaNoSeInventaNada() {
        when(client.status(ENVIO)).thenThrow(new RuntimeException("502"));

        ConfirmacionDelProveedor resultado = confirmacion.porEnvio(ENVIO);

        assertThat(resultado.terminado()).isFalse();
        assertThat(resultado.sinRastro()).isFalse();
        assertThat(resultado.porRed()).isEmpty();
    }

    // ---------- El historial ----------

    @Test
    void elHistorialSePuedePedirPorEnvioYSusFilasSonFinales() {
        when(client.historial()).thenReturn(Map.of("history", List.of(
                publicada("tiktok", "7686586526137945362", "https://tt/1"),
                // De otro envío del mismo perfil: no cuenta.
                fila("facebook", true, Map.of("request_id", "otro-envio", "platform_post_id", "x")),
                // De este envío y en false sin más: en el historial eso ya es final.
                fila("instagram", false, Map.of()))));

        ConfirmacionDelProveedor resultado = confirmacion.porHistorial(ENVIO);

        assertThat(resultado.terminado()).isFalse();
        assertThat(resultado.porRed()).containsOnlyKeys(Platform.TIKTOK, Platform.INSTAGRAM);
        assertThat(resultado.porRed().get(Platform.TIKTOK).postId()).isEqualTo("7686586526137945362");
        assertThat(resultado.porRed().get(Platform.INSTAGRAM).publicada()).isFalse();
    }

    @Test
    void elHistorialPorPerfilDescartaLoDeOtroNegocioYLoDeAntes() {
        when(client.historial()).thenReturn(Map.of("history", List.of(
                fila("tiktok", true, Map.of("profile_username", "otro-negocio", "platform_post_id", "de-otro")),
                fila("youtube", true, Map.of("upload_timestamp", "2026-09-17T10:00:00.000Z", "platform_post_id", "vieja")),
                publicada("facebook", "buena", "https://fb/buena"))));

        ConfirmacionDelProveedor resultado = confirmacion.porHistorial(
                PERFIL, LocalDateTime.of(2026, 9, 17, 19, 0));

        assertThat(resultado.porRed()).containsOnlyKeys(Platform.FACEBOOK);
        assertThat(resultado.porRed().get(Platform.FACEBOOK).postId()).isEqualTo("buena");
    }

    @Test
    void elHistorialPorPerfilSinHoraNoSeAcepta() {
        // Sin hora se tomaría la publicación anterior del mismo negocio como
        // si fuera esta.
        assertThat(confirmacion.porHistorial(PERFIL, null).porRed()).isEmpty();
    }

    @Test
    void laFechaEnvueltaDelEstadoSeEntiendeIgual() {
        // En /status la fecha viene como {"$date": "..."}; en el historial
        // como texto. Es el mismo instante y se lee igual.
        when(client.historial()).thenReturn(Map.of("history", List.of(
                fila("facebook", true, Map.of(
                        "platform_post_id", "999",
                        "upload_timestamp", Map.of("$date", "2026-09-17T19:21:35.936Z"))))));

        assertThat(confirmacion.porHistorial(PERFIL, LocalDateTime.of(2026, 9, 17, 19, 0))
                .porRed().get(Platform.FACEBOOK).postId()).isEqualTo("999");
    }

    @Test
    void unaRedQueNoOfrecemosSeIgnoraSinRomper() {
        when(client.status(ENVIO)).thenReturn(estado("completed", 2, 2, List.of(
                publicada("threads", "1", "https://threads/1"),
                publicada("facebook", "2", "https://fb/2"))));

        assertThat(confirmacion.porEnvio(ENVIO).porRed()).containsOnlyKeys(Platform.FACEBOOK);
    }

    // ---------- Juntar fuentes ----------

    @Test
    void completarConOtraFuenteSoloRellenaLoQueFaltaba() {
        ConfirmacionDelProveedor estado = new ConfirmacionDelProveedor(true, false, Map.of(
                Platform.FACEBOOK, ResultadoDeRed.publicada("fb-estado", "https://fb/estado")));
        ConfirmacionDelProveedor historial = new ConfirmacionDelProveedor(false, false, Map.of(
                Platform.FACEBOOK, ResultadoDeRed.publicada("fb-historial", null),
                Platform.TIKTOK, ResultadoDeRed.publicada("tt-historial", "https://tt/h")));

        ConfirmacionDelProveedor juntas = estado.completadaCon(historial);

        assertThat(juntas.terminado()).isTrue();
        assertThat(juntas.porRed().get(Platform.FACEBOOK).postId()).isEqualTo("fb-estado");
        assertThat(juntas.porRed().get(Platform.TIKTOK).postId()).isEqualTo("tt-historial");
    }
}

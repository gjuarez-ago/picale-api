package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.enums.PostFormat;
import com.sun.net.httpserver.HttpServer;

/**
 * Un video grande no se baja a memoria: se le pasa el enlace al proveedor.
 *
 * <p>Sale de un fallo real (28 oct 2026): un video de 1.3 GB de la cuenta de
 * Juan Rodríguez no salió en ninguna red porque el servidor intentaba bajarlo
 * entero, con dos minutos de plazo, antes de mandarlo.
 */
class VideoGrandePorEnlaceTest {

    private HttpServer servidor;
    private String base;
    private final AtomicReference<String> cuerpo = new AtomicReference<>();
    private final AtomicBoolean seBajo = new AtomicBoolean();

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/media/grande.mp4", ex -> {
            if (!"HEAD".equals(ex.getRequestMethod())) {
                seBajo.set(true);
            }
            ex.getResponseHeaders().add("Content-Length", String.valueOf(1_325_255_439L));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        servidor.createContext("/api/upload", ex -> {
            cuerpo.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] r = "{\"success\":true,\"request_id\":\"r1\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterEach
    void bajar() {
        servidor.stop(0);
    }

    @Test
    @DisplayName("un video de más de 200 MB viaja como enlace y nunca se baja")
    void viajaComoEnlace() {
        UploadPostProperties props = new UploadPostProperties();
        props.setBaseUrl(base + "/api");
        props.setApiKey("k");
        UploadPostClient cliente = new UploadPostClient(props);
        String url = base + "/media/grande.mp4";

        assertThat(cliente.pesoDe(url)).isEqualTo(1_325_255_439L);
        Map<String, Object> r = cliente.publishVideo("perfil", List.of("youtube", "tiktok"), "Título",
                Map.of(), url, PostFormat.REEL, 4000, Ubicacion.NINGUNA, false, 521);

        assertThat(r).containsEntry("request_id", "r1");
        assertThat(seBajo).isFalse();
        assertThat(cuerpo.get()).contains("name=\"video\"").contains(url);
    }

    @Test
    @DisplayName("si no se sabe el peso, se sigue el camino de siempre")
    void pesoDesconocido() {
        UploadPostProperties props = new UploadPostProperties();
        props.setBaseUrl(base + "/api");
        UploadPostClient cliente = new UploadPostClient(props);

        assertThat(cliente.pesoDe(base + "/no-existe.mp4")).isEqualTo(-1);
        assertThat(cliente.pesoDe("no es un enlace")).isEqualTo(-1);
    }
}

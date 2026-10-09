package com.metricol.api.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.models.response.ApiResponse;

/**
 * Qué commit está corriendo este servidor, para no tener que adivinarlo.
 *
 * <p>Antes de esto, "qué versión hay en producción" era algo que se infería:
 * quien desplegó recordaba (o no) qué commit era {@code HEAD} en ese momento.
 * Con el tiempo, o si despliega otra persona, esa memoria deja de servir. Este
 * endpoint lo convierte en un hecho que se puede preguntar, no en algo que hay
 * que recordar.
 *
 * <p>Público y sin llave a propósito: el SHA de un commit no es un secreto —
 * está en GitHub si el repositorio es público— y así lo puede consultar
 * cualquier script, no solo quien tenga la llave de operaciones.
 */
@RestController
public class VersionController {

    private final String sha;
    private final String base;

    public VersionController(@Value("${app.build.sha-file:/app/BUILD_SHA}") String archivoDelSha,
            @Value("${spring.datasource.url:}") String urlDeLaBase) {
        this.sha = leer(archivoDelSha);
        this.base = nombreDeLaBase(urlDeLaBase);
    }

    @GetMapping("/api/v1/ops/version")
    public ResponseEntity<ApiResponse<Map<String, String>>> version() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("sha", sha, "base", base)));
    }

    /**
     * Solo el NOMBRE de la base, nunca el host, el usuario ni la contraseña.
     *
     * <p>Está aquí por lo que pasó el 9 oct 2026: un despliegue a mano levantó
     * la API contra otra base y estuvo seis horas así. Todo respondía 200 y la
     * salud estaba en verde — el único síntoma era que a la gente le aparecían
     * datos que no eran suyos. Con esto, comprobar un despliegue deja de ser
     * "responde" y pasa a ser "responde, con este commit y contra esta base".
     *
     * <p>El nombre de una base no es un secreto; las credenciales sí, y por eso
     * se recorta la URL y solo se publica lo último.
     */
    static String nombreDeLaBase(String url) {
        if (url == null || url.isBlank()) {
            return "desconocida";
        }
        String sinParametros = url.split("\\?")[0];
        int barra = sinParametros.lastIndexOf('/');
        if (barra < 0 || barra == sinParametros.length() - 1) {
            return "desconocida";
        }
        return sinParametros.substring(barra + 1);
    }

    /**
     * Sin el archivo (una corrida local, fuera de Docker, o un despliegue
     * viejo de antes de que esto existiera) no se cae nada: se contesta
     * "desconocido" y ya.
     */
    private static String leer(String ruta) {
        try {
            String contenido = Files.readString(Path.of(ruta)).strip();
            return contenido.isEmpty() ? "desconocido" : contenido;
        } catch (IOException ex) {
            return "desconocido";
        }
    }
}

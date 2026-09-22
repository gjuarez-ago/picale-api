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

    public VersionController(@Value("${app.build.sha-file:/app/BUILD_SHA}") String archivoDelSha) {
        this.sha = leer(archivoDelSha);
    }

    @GetMapping("/api/v1/ops/version")
    public ResponseEntity<ApiResponse<Map<String, String>>> version() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("sha", sha)));
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

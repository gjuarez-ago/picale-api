package com.metricol.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.metricol.api.models.response.ApiResponse;

/** El SHA de lo que corre: se lee una vez del archivo que deja el build, sin llave ni sesión. */
class VersionControllerTest {

    @Test
    @DisplayName("con el archivo puesto, contesta el SHA que trae")
    void conElArchivo(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path archivo = dir.resolve("BUILD_SHA");
        Files.writeString(archivo, "e93414d1234567890\n");

        VersionController c = new VersionController(archivo.toString());
        ResponseEntity<ApiResponse<Map<String, String>>> respuesta = c.version();

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody().isOk()).isTrue();
        assertThat(respuesta.getBody().getResult().get("sha")).isEqualTo("e93414d1234567890");
    }

    @Test
    @DisplayName("sin el archivo (corrida local, o un despliegue de antes de esto) no se cae: contesta 'desconocido'")
    void sinElArchivo() {
        VersionController c = new VersionController("/una/ruta/que/no/existe/BUILD_SHA");

        assertThat(c.version().getBody().getResult().get("sha")).isEqualTo("desconocido");
    }

    @Test
    @DisplayName("un archivo vacio tampoco se toma como un sha valido")
    void archivoVacio(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path archivo = dir.resolve("BUILD_SHA");
        Files.writeString(archivo, "   \n");

        VersionController c = new VersionController(archivo.toString());

        assertThat(c.version().getBody().getResult().get("sha")).isEqualTo("desconocido");
    }
}

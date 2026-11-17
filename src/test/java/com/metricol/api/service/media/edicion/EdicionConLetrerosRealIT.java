package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.EvidenciaDeHablante;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;
import com.metricol.api.service.media.subtitulos.DisenoVideo;
import com.metricol.api.service.media.subtitulos.EstiloSubtitulos;
import com.metricol.api.service.media.subtitulos.GuionAss;
import com.metricol.api.service.media.subtitulos.LecturaDeWhisper;
import com.metricol.api.service.media.subtitulos.Letrero;
import com.metricol.api.service.media.subtitulos.Palabra;

/**
 * Canario local explícito: un original limpio, su transcripción y su evidencia
 * de hablante, y sale una copia con subtítulos, palabras clave, letreros y
 * acercamientos elegidos solos. No toca cuentas ni el bucket.
 *
 * <p>Entradas en {@code target/subtitulos-pro-local}: {@code original.mp4},
 * {@code whisper.json} (respuesta de la transcripción) y {@code analisis.json}
 * (worker de hablante). La decisión editorial se guarda en
 * {@code seleccion-editorial.json}: si ya existe se reutiliza (respuesta
 * grabada); si no, se pide al proveedor con la llave del {@code .env}.
 *
 * <p>{@code -Destilo=CAJA|PALABRA|REBOTE|CLASICO} y {@code -Dcolor=#RRGGBB}.
 */
class EdicionConLetrerosRealIT {

    @Test
    void copiaConSubtitulosLetrerosYAcercamientosElegidosSolos() throws Exception {
        Path carpeta = Path.of(System.getProperty("carpeta", "target/subtitulos-pro-local")).toAbsolutePath();
        Path original = carpeta.resolve("original.mp4");
        var json = new ObjectMapper();
        var media = new MediaAdaptProperties();
        var ffmpeg = new FfmpegImagen(media);
        EstiloSubtitulos estilo = EstiloSubtitulos.valueOf(System.getProperty("estilo", "CAJA"));
        String color = System.getProperty("color", "#FFD400");
        // El look: -Dletra, -Dtamano, -Dcantidad, -DcolorLetreros y -Dresalte; -Dnombre para el archivo.
        var diseno = new DisenoVideo(System.getProperty("resalte"), System.getProperty("colorLetreros"),
                opcion(DisenoVideo.Letra.class, "letra"), opcion(DisenoVideo.Tamano.class, "tamano"),
                opcion(DisenoVideo.Cantidad.class, "cantidad"));
        boolean conLetreros = !"no".equals(System.getProperty("letreros"));
        boolean conClaves = !"no".equals(System.getProperty("claves"));
        String nombre = System.getProperty("nombre", estilo.name());

        // Las palabras ya guardadas de un video (palabras.json) o la respuesta cruda de la transcripción.
        List<Palabra> palabras = Files.exists(carpeta.resolve("palabras.json"))
                ? json.readValue(carpeta.resolve("palabras.json").toFile(), new TypeReference<List<Palabra>>() { })
                : LecturaDeWhisper.palabras(json.readValue(carpeta.resolve("whisper.json").toFile(),
                        new TypeReference<Map<String, Object>>() { }));
        assertThat(palabras).isNotEmpty();
        var informe = new EjecutorAnalisisHablante(new AnalisisHablanteProperties(), media)
                .leerInforme(carpeta.resolve("analisis.json"));
        var medidas = ffmpeg.medirVideo(original.toString());
        String huella = RenderizadorDeAcercamientos.hash(original);
        informe.validarContra(huella, medidas.segundos());

        // 1. Ideas y decisión editorial.
        var ideas = SelectorDeMomentos.ideas(palabras);
        var cantidad = diseno.letrerosONormal();
        Path grabada = carpeta.resolve("seleccion-editorial" + (cantidad == DisenoVideo.Cantidad.NORMAL ? ""
                : "-" + cantidad) + ".json");
        boolean deProveedor = !Files.exists(grabada);
        if (deProveedor) {
            Files.writeString(grabada, pedir(SelectorDeMomentos.SISTEMA,
                    SelectorDeMomentos.peticion(ideas, medidas.segundos(), informe.intervals(), cantidad)), StandardCharsets.UTF_8);
        }
        var seleccion = SelectorDeMomentos.leer(Files.readString(grabada, StandardCharsets.UTF_8), ideas);

        // 2. Tiempos, ritmo y límites: del código.
        var resuelto = SelectorDeMomentos.resolver(palabras, ideas, seleccion, informe.intervals(), 0,
                medidas.segundos(), cantidad.ritmo());

        // Cada letrero, encima de la cabeza de quien habla en ese rato.
        var letreros = !conLetreros ? List.<Letrero>of() : resuelto.letreros().stream().map(l -> {
            boolean conZoom = resuelto.eventos().stream().anyMatch(e -> Math.abs(e.start() - l.inicio()) < 0.01);
            return new Letrero(l.tipo(), l.texto(), l.inicio(), l.fin(), SelectorDeMomentos.alturaSobreRostro(
                    informe, l.inicio(), l.fin(), conZoom ? SelectorDeMomentos.ESCALA : 1));
        }).toList();

        // 3. Guion con subtítulos, palabras clave y letreros.
        Path guion = carpeta.resolve("guion-" + nombre + ".ass");
        var paraPintar = conClaves ? resuelto.palabras() : palabras;
        Files.writeString(guion, GuionAss.escribir(paraPintar, estilo, medidas.ancho(), medidas.alto(),
                color, letreros, diseno), StandardCharsets.UTF_8);

        // 4. Render: con acercamientos por la receta validada; sin ellos, la edición de siempre.
        Path destino = carpeta.resolve("editado-" + nombre + "-" + UUID.randomUUID().toString().substring(0, 8)
                + ".mp4");
        UUID cuenta = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var eventos = resuelto.eventos();
        if (eventos.isEmpty()) {
            assertThat(ffmpeg.subtitularVideo(original.toString(), guion, destino)).isTrue();
        } else {
            var tramo = new Tramo(0, informe.durationSeconds());
            var contexto = new ContextoEdicionVideo(cuenta, UUID.fromString("20000000-0000-0000-0000-000000000002"),
                    huella, "transcript-local", informe.durationSeconds(), tramo, false,
                    new EvidenciaDeHablante(informe.evidenceRevision(), informe.detectorVersion(), true,
                            informe.intervals()));
            var plan = new PlanDeEdicionVideo(PlanDeEdicionVideo.SCHEMA_VERSION, cuenta, contexto.sourceAssetId(),
                    huella, "transcript-local", informe.evidenceRevision(),
                    PerfilEdicionDinamica.SUBTLE.policyVersion(), PlanDeEdicionVideo.TEMPLATE_CATALOG_VERSION,
                    PerfilEdicionDinamica.SUBTLE, PlanDeEdicionVideo.TimeBase.OUTPUT_SECONDS, tramo, eventos);
            var propiedades = new EdicionDinamicaProperties();
            propiedades.setEnabled(true);
            propiedades.setPilotWorkspaces(Set.of(cuenta));
            var control = new ControlDeEdicionDinamica(propiedades);
            var preparado = new PreparadorDePlanDeEdicion(control, new ValidadorDePlanDeEdicion())
                    .preparar(json.writeValueAsString(plan), contexto);
            var resultado = new RenderizadorDeAcercamientos(
                    new CompiladorDeAcercamientos(new ValidadorDePlanDeEdicion()), ffmpeg, control)
                    .renderizar(original, destino, preparado, contexto, informe, guion, null, null, 1);
            assertThat(resultado.movimientos()).hasSameSizeAs(eventos);
        }
        assertThat(RenderizadorDeAcercamientos.hash(original)).isEqualTo(huella);

        var informeFinal = new LinkedHashMap<String, Object>();
        informeFinal.put("salida", destino.toString());
        informeFinal.put("estilo", estilo);
        informeFinal.put("decisionDe", deProveedor ? "proveedor" : "respuesta grabada");
        informeFinal.put("ideas", ideas);
        informeFinal.put("seleccion", seleccion);
        informeFinal.put("acercamientos", eventos);
        informeFinal.put("letreros", letreros);
        informeFinal.put("palabrasClave", resuelto.palabras().stream().filter(Palabra::clave).map(Palabra::texto)
                .toList());
        informeFinal.put("descartes", resuelto.descartes());
        Files.writeString(carpeta.resolve("informe-" + nombre + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(informeFinal), StandardCharsets.UTF_8);
    }

    private static <E extends Enum<E>> E opcion(Class<E> tipo, String propiedad) {
        String valor = System.getProperty(propiedad);
        return valor == null || valor.isBlank() ? null : Enum.valueOf(tipo, valor);
    }

    /** Solo para este canario: la llamada editorial directa, con la llave y el modelo del .env local. */
    private static String pedir(String sistema, String usuario) throws Exception {
        String llave = null;
        String modelo = "gpt-4o-mini";
        for (String linea : Files.readAllLines(Path.of(".env"), StandardCharsets.UTF_8)) {
            String l = linea.strip();
            if (l.startsWith("OPENAI_API_KEY=")) llave = l.substring(15).replace("\"", "").strip();
            if (l.startsWith("OPENAI_API_MODEL=")) modelo = l.substring(17).replace("\"", "").strip();
        }
        assertThat(llave).as("OPENAI_API_KEY en .env").isNotBlank();
        var json = new ObjectMapper();
        var cuerpo = new LinkedHashMap<String, Object>();
        cuerpo.put("model", modelo);
        cuerpo.put("response_format", Map.of("type", "json_object"));
        cuerpo.put("messages", List.of(Map.of("role", "system", "content", sistema),
                Map.of("role", "user", "content", usuario)));
        var respuesta = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + llave).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(respuesta.statusCode()).as(respuesta.body()).isEqualTo(200);
        return json.readTree(respuesta.body()).path("choices").path(0).path("message").path("content").asText();
    }
}

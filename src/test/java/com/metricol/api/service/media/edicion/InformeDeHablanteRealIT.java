package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.EvidenciaDeHablante;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Evento;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Motivo;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.TipoEfecto;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

/** Prueba optativa explícita contra archivos reales locales. No usa ni escribe en una base o bucket. */
class InformeDeHablanteRealIT {
    @Test
    void originalAutorizadoInformeRealYPlanV1ConservanLaMismaIdentidad() throws Exception {
        Path directory = Path.of(System.getProperty("speaker.real.directory",
                "target/edicion-dinamica-local"));
        var json = new ObjectMapper();
        var metadata = json.readTree(directory.resolve("original-seleccionado.json").toFile());
        Path original = Path.of(metadata.path("local_path").asText());
        assertThat(original).isRegularFile();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(original)) {
            byte[] block = new byte[64 * 1024];
            int n;
            while ((n = input.read(block)) != -1) hash.update(block, 0, n);
        }
        String sourceHash = HexFormat.of().formatHex(hash.digest());
        assertThat(sourceHash).isEqualTo(metadata.path("local_sha256").asText());
        assertThat(metadata.path("source_asset_id").isNull()).isTrue();
        assertThat(metadata.path("status").asText()).isEqualTo("READY");
        UUID ws = UUID.fromString(metadata.path("workspace_id").asText());
        UUID asset = UUID.fromString(metadata.path("asset_id").asText());
        var media = new MediaAdaptProperties();
        var medidas = new FfmpegImagen(media).medirVideo(original.toString());
        assertThat(medidas).isNotNull();
        var informe = new EjecutorAnalisisHablante(new AnalisisHablanteProperties(), media)
                .leerInforme(directory.resolve("original-analisis.json"));
        informe.validarContra(sourceHash, medidas.segundos());
        assertThat(informe.activeSpeakerVerified()).isTrue();
        var texto = json.readTree(directory.resolve("transcripcion-original.json").toFile());
        assertThat(texto.path("workspace_id").asText()).isEqualTo(ws.toString());
        assertThat(texto.path("asset_id").asText()).isEqualTo(asset.toString());
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(texto.path("transcript").asText("").getBytes(StandardCharsets.UTF_8)));
        var contexto = new ContextoEdicionVideo(ws, asset, sourceHash, revision, medidas.segundos(),
                new Tramo(0, medidas.segundos()), false,
                new EvidenciaDeHablante(informe.evidenceRevision(), informe.detectorVersion(), true, informe.intervals()));
        var intervalo = informe.intervals().get(0);
        var evento = new Evento(TipoEfecto.PUNCH_IN, intervalo.start(), intervalo.start() + 2,
                1.12, TipoEfecto.PUNCH_IN.templateVersion(), intervalo.focusTrackRef(), Motivo.KEY_STATEMENT);
        var plan = new PlanDeEdicionVideo(PlanDeEdicionVideo.SCHEMA_VERSION, ws, asset, sourceHash, revision,
                informe.evidenceRevision(), PerfilEdicionDinamica.SUBTLE.policyVersion(),
                PlanDeEdicionVideo.TEMPLATE_CATALOG_VERSION, PerfilEdicionDinamica.SUBTLE,
                PlanDeEdicionVideo.TimeBase.OUTPUT_SECONDS, contexto.sourceRange(), List.of(evento));
        var properties = new EdicionDinamicaProperties();
        properties.setEnabled(true);
        properties.setPilotWorkspaces(Set.of(ws));
        var prepared = new PreparadorDePlanDeEdicion(new ControlDeEdicionDinamica(properties),
                new ValidadorDePlanDeEdicion()).preparar(json.writeValueAsString(plan), contexto);
        assertThat(prepared.plan().sourceRevision()).isEqualTo(sourceHash);
        Files.writeString(directory.resolve("plan-real-preparado.json"), prepared.canonicalJson(), StandardCharsets.UTF_8);
    }
}

package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

/** Canario local explícito: original de Juan y evidencia reales, sin acceso a cuenta/bucket para escribir. */
class RenderAcercamientosRealIT {
    @Test
    void renderizaTresEnfasisSobreUnaCopiaDelOriginalDeJuan() throws Exception {
        Path input = Path.of("target/edicion-dinamica-local");
        Path output = Path.of("target/edicion-dinamica-render").toAbsolutePath();
        Files.createDirectories(output);
        var json = new ObjectMapper();
        var metadata = json.readTree(input.resolve("original-seleccionado.json").toFile());
        Path original = Path.of(metadata.path("local_path").asText());
        var base = json.readValue(input.resolve("plan-real-preparado.json").toFile(), PlanDeEdicionVideo.class);
        var media = new MediaAdaptProperties();
        var ffmpeg = new FfmpegImagen(media);
        var report = new EjecutorAnalisisHablante(new AnalisisHablanteProperties(), media)
                .leerInforme(input.resolve("original-analisis.json"));
        var contexto = new ContextoEdicionVideo(base.workspaceId(), base.sourceAssetId(), base.sourceRevision(),
                base.transcriptRevision(), report.durationSeconds(), base.sourceRange(), false,
                new EvidenciaDeHablante(report.evidenceRevision(), report.detectorVersion(), true, report.intervals()));
        var events = List.of(event(19.92, 22.72), event(38.56, 41.36), event(79.8, 82.6));
        var plan = new PlanDeEdicionVideo(base.schemaVersion(), base.workspaceId(), base.sourceAssetId(), base.sourceRevision(),
                base.transcriptRevision(), base.evidenceRevision(), base.policyVersion(), base.templateCatalogVersion(),
                base.profile(), base.timeBase(), base.sourceRange(), events);
        var properties = new EdicionDinamicaProperties();
        properties.setEnabled(true);
        properties.setPilotWorkspaces(Set.of(base.workspaceId()));
        var control = new ControlDeEdicionDinamica(properties);
        var prepared = new PreparadorDePlanDeEdicion(control, new ValidadorDePlanDeEdicion())
                .preparar(json.writeValueAsString(plan), contexto);
        Path target = output.resolve("juan-acercamientos-" + UUID.randomUUID() + ".mp4");
        var renderer = new RenderizadorDeAcercamientos(new CompiladorDeAcercamientos(new ValidadorDePlanDeEdicion()), ffmpeg, control);
        var result = renderer.renderizar(original, target, prepared, contexto, report, null, null, null, 1);
        assertThat(result.width()).isEqualTo(1080);
        assertThat(result.height()).isEqualTo(1920);
        assertThat(result.movimientos()).hasSize(3);
        assertThat(RenderizadorDeAcercamientos.hash(original)).isEqualTo(metadata.path("local_sha256").asText());
        Files.writeString(output.resolve("plan-render-real.json"), prepared.canonicalJson(), StandardCharsets.UTF_8);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("result", result);
        evidence.put("outputPath", target.toString());
        evidence.put("originalPath", original.toString());
        evidence.put("sourceSha256", contexto.sourceRevision());
        evidence.put("evidenceRevision", report.evidenceRevision());
        Files.writeString(output.resolve("resultado-real.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
                StandardCharsets.UTF_8);
    }

    @Test
    void muestraCortaDeVeinticincoSegundosConDosIntervenciones() throws Exception {
        Path input = Path.of("target/edicion-dinamica-local");
        Path output = Path.of("target/edicion-dinamica-render").toAbsolutePath();
        Files.createDirectories(output);
        var json = new ObjectMapper();
        var metadata = json.readTree(input.resolve("original-seleccionado.json").toFile());
        Path original = Path.of(metadata.path("local_path").asText());
        var base = json.readValue(input.resolve("plan-real-preparado.json").toFile(), PlanDeEdicionVideo.class);
        var media = new MediaAdaptProperties();
        var report = new EjecutorAnalisisHablante(new AnalisisHablanteProperties(), media)
                .leerInforme(input.resolve("original-analisis.json"));
        var range = new Tramo(18, 43);
        var contexto = new ContextoEdicionVideo(base.workspaceId(), base.sourceAssetId(), base.sourceRevision(),
                base.transcriptRevision(), report.durationSeconds(), range, false,
                new EvidenciaDeHablante(report.evidenceRevision(), report.detectorVersion(), true, report.intervals()));
        var plan = new PlanDeEdicionVideo(base.schemaVersion(), base.workspaceId(), base.sourceAssetId(), base.sourceRevision(),
                base.transcriptRevision(), base.evidenceRevision(), base.policyVersion(), base.templateCatalogVersion(),
                base.profile(), base.timeBase(), range, List.of(event(1.92, 4.72), event(20.56, 23.36)));
        var properties = new EdicionDinamicaProperties();
        properties.setEnabled(true);
        properties.setPilotWorkspaces(Set.of(base.workspaceId()));
        var control = new ControlDeEdicionDinamica(properties);
        var prepared = new PreparadorDePlanDeEdicion(control, new ValidadorDePlanDeEdicion())
                .preparar(json.writeValueAsString(plan), contexto);
        Path target = output.resolve("juan-muestra-25s-" + UUID.randomUUID() + ".mp4");
        var renderer = new RenderizadorDeAcercamientos(new CompiladorDeAcercamientos(new ValidadorDePlanDeEdicion()),
                new FfmpegImagen(media), control);
        var result = renderer.renderizar(original, target, prepared, contexto, report, null, null, null, 1);
        assertThat(result.width()).isEqualTo(1080);
        assertThat(result.height()).isEqualTo(1920);
        assertThat(result.durationSeconds()).isBetween(24.96, 25.04);
        assertThat(result.movimientos()).hasSize(2);
        assertThat(RenderizadorDeAcercamientos.hash(original)).isEqualTo(metadata.path("local_sha256").asText());
        Files.writeString(output.resolve("plan-render-corto.json"), prepared.canonicalJson(), StandardCharsets.UTF_8);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("result", result);
        evidence.put("outputPath", target.toString());
        evidence.put("originalPath", original.toString());
        evidence.put("sourceRange", range);
        Files.writeString(output.resolve("resultado-corto.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
                StandardCharsets.UTF_8);
    }

    private static Evento event(double start, double end) {
        return new Evento(TipoEfecto.PUNCH_IN, start, end, 1.12, TipoEfecto.PUNCH_IN.templateVersion(),
                "person-1-1", Motivo.KEY_STATEMENT);
    }
}

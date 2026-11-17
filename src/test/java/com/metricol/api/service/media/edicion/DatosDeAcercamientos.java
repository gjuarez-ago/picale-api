package com.metricol.api.service.media.edicion;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.EvidenciaDeHablante;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Evento;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;
import com.metricol.api.service.media.edicion.PreparadorDePlanDeEdicion.PlanPreparado;

final class DatosDeAcercamientos {
    record Caso(PlanPreparado preparado, ContextoEdicionVideo contexto, InformeDeHablante informe,
            ControlDeEdicionDinamica control) { }

    static Caso crear(String hash, Tramo tramo, List<Evento> eventos) throws Exception {
        var v = DatosDeInformeHablante.valido();
        var informe = new InformeDeHablante(v.schemaVersion(), v.timeBase(), v.detectorVersion(), v.evidenceRevision(),
                hash, v.durationSeconds(), v.normalization(), true, v.intervals(), v.samples(), v.modelRevisions(), 1);
        return crear(informe, tramo, eventos);
    }

    static Caso crear(InformeDeHablante informe, Tramo tramo, List<Evento> eventos) throws Exception {
        var contexto = new ContextoEdicionVideo(DatosDeEdicion.WS, DatosDeEdicion.ASSET, informe.sourceSha256(),
                "transcript-v1", informe.durationSeconds(), tramo, false, new EvidenciaDeHablante(
                        informe.evidenceRevision(), informe.detectorVersion(), true, informe.intervals()));
        var plan = new PlanDeEdicionVideo(PlanDeEdicionVideo.SCHEMA_VERSION, contexto.workspaceId(), contexto.sourceAssetId(),
                contexto.sourceRevision(), contexto.transcriptRevision(), informe.evidenceRevision(),
                PerfilEdicionDinamica.SUBTLE.policyVersion(), PlanDeEdicionVideo.TEMPLATE_CATALOG_VERSION,
                PerfilEdicionDinamica.SUBTLE, PlanDeEdicionVideo.TimeBase.OUTPUT_SECONDS, tramo, eventos);
        var properties = new EdicionDinamicaProperties();
        properties.setEnabled(true);
        properties.setPilotWorkspaces(Set.of(DatosDeEdicion.WS));
        var control = new ControlDeEdicionDinamica(properties);
        var prepared = new PreparadorDePlanDeEdicion(control, new ValidadorDePlanDeEdicion())
                .preparar(new ObjectMapper().writeValueAsString(plan), contexto);
        return new Caso(prepared, contexto, informe, control);
    }

    static Caso crear() throws Exception {
        return crear("a".repeat(64), new Tramo(0, 4), List.of(DatosDeEdicion.zoom(.5)));
    }
}

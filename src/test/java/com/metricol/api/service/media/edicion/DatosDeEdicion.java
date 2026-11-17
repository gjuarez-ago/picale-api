package com.metricol.api.service.media.edicion;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.EvidenciaDeHablante;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.IntervaloHablante;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Evento;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Motivo;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.TipoEfecto;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

/** Datos sintéticos de contrato; no son resultados de un detector ejecutado. */
final class DatosDeEdicion {
    static final UUID WS = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID ASSET = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final String HASH = "a".repeat(64);

    static ContextoEdicionVideo contexto() {
        return contexto(new Tramo(0, 120), new EvidenciaDeHablante("evidence-v1", "test-detector-v1",
                true, List.of(new IntervaloHablante(0, 120, "person-1"))));
    }

    static ContextoEdicionVideo contexto(Tramo tramo, EvidenciaDeHablante evidencia) {
        return new ContextoEdicionVideo(WS, ASSET, HASH, "transcript-v1", 120, tramo, false, evidencia);
    }

    static Evento zoom(double inicio) {
        return evento(TipoEfecto.PUNCH_IN, inicio, inicio + 2, 1.12, "person-1");
    }

    static Evento evento(TipoEfecto tipo, double inicio, double fin, double escala, String track) {
        return new Evento(tipo, inicio, fin, escala, tipo.templateVersion(), track,
                tipo == TipoEfecto.CARD_INTRO ? Motivo.OPENING : Motivo.KEY_STATEMENT);
    }

    static PlanDeEdicionVideo plan() {
        return plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), List.of(zoom(10), zoom(40)));
    }

    static PlanDeEdicionVideo plan(PerfilEdicionDinamica perfil, Tramo tramo, List<Evento> eventos) {
        return new PlanDeEdicionVideo(PlanDeEdicionVideo.SCHEMA_VERSION, WS, ASSET, HASH,
                "transcript-v1", "evidence-v1", perfil.policyVersion(),
                PlanDeEdicionVideo.TEMPLATE_CATALOG_VERSION, perfil,
                PlanDeEdicionVideo.TimeBase.OUTPUT_SECONDS, tramo, eventos);
    }

    static String json(PlanDeEdicionVideo plan) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(plan);
    }

    static PreparadorDePlanDeEdicion preparadorHabilitado() {
        var propiedades = new EdicionDinamicaProperties();
        propiedades.setEnabled(true);
        propiedades.setPilotWorkspaces(java.util.Set.of(WS));
        return new PreparadorDePlanDeEdicion(new ControlDeEdicionDinamica(propiedades),
                new ValidadorDePlanDeEdicion());
    }
}

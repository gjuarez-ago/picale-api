package com.metricol.api.service.media.edicion;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.IntervaloHablante;

final class DatosDeInformeHablante {
    static InformeDeHablante valido() {
        return new InformeDeHablante("speaker-analysis-v1", "SOURCE_SECONDS", InformeDeHablante.DETECTOR_VERSION,
                "b".repeat(64), "a".repeat(64), 4, new ObjectMapper().createObjectNode().put("fps", 25),
                true, List.of(new IntervaloHablante(0, 4, "person-1")),
                IntStream.range(0, 100).mapToObj(i -> new InformeDeHablante.Muestra(i / 25.0, 1, "person-1", 1,
                        List.of(0.2, 0.2, 0.4, 0.4), 0.1, 0.99, true, 0.99, true)).toList(),
                Map.of("face_landmarker.task", "c".repeat(64), "silero_vad.onnx", "d".repeat(64),
                        "light-asd-v1.onnx", "e".repeat(64)), 1.0);
    }

    static InformeDeHablante conMuestras(List<InformeDeHablante.Muestra> samples) {
        var v = valido();
        return new InformeDeHablante(v.schemaVersion(), v.timeBase(), v.detectorVersion(), v.evidenceRevision(),
                v.sourceSha256(), v.durationSeconds(), v.normalization(), v.activeSpeakerVerified(),
                v.intervals(), samples, v.modelRevisions(), v.elapsedSeconds());
    }
}

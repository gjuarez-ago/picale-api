package com.metricol.api.service.media.edicion;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.IntervaloHablante;

/** Evidencia del worker local; se contrasta con el archivo descargado antes de crear contexto. */
public record InformeDeHablante(String schemaVersion, String timeBase, String detectorVersion,
        String evidenceRevision, String sourceSha256, double durationSeconds, JsonNode normalization,
        boolean activeSpeakerVerified, List<IntervaloHablante> intervals, List<Muestra> samples,
        Map<String, String> modelRevisions, double elapsedSeconds) {
    public static final String DETECTOR_VERSION = "mp-light-asd-silero-v2";

    public InformeDeHablante {
        intervals = intervals == null ? null : List.copyOf(intervals);
        samples = samples == null ? null : List.copyOf(samples);
        modelRevisions = modelRevisions == null ? null : Map.copyOf(modelRevisions);
    }

    public record Muestra(double time, int scene, String track, int faceCount, List<Double> box,
            double mouthOpening, double voiceProbability, boolean speech,
            double activeSpeakerProbability, boolean mouthMotion) {
        public Muestra {
            box = box == null ? null : List.copyOf(box);
        }
    }

    public void validarContra(String sourceHash, double sourceDuration) {
        exigir("speaker-analysis-v1".equals(schemaVersion) && "SOURCE_SECONDS".equals(timeBase)
                && DETECTOR_VERSION.equals(detectorVersion), "Versión de evidencia no admitida.");
        exigir(hash(evidenceRevision) && hash(sourceSha256) && Objects.equals(sourceHash, sourceSha256),
                "La evidencia no corresponde al original descargado.");
        exigir(Double.isFinite(durationSeconds) && durationSeconds > 0
                && durationSeconds <= sourceDuration + 0.000001
                && Math.abs(durationSeconds - sourceDuration) <= 0.041,
                "La duración de la evidencia no coincide con el original.");
        exigir(normalization != null && normalization.path("fps").asInt() == 25,
                "Normalización de evidencia no admitida.");
        exigir(modelRevisions != null && modelRevisions.keySet().equals(Set.of(
                "face_landmarker.task", "silero_vad.onnx", "light-asd-v1.onnx"))
                && modelRevisions.values().stream().allMatch(InformeDeHablante::hash),
                "Faltan revisiones de los modelos.");
        exigir(Double.isFinite(elapsedSeconds) && elapsedSeconds >= 0, "Tiempo de análisis inválido.");
        exigir(intervals != null && intervals.size() <= ValidadorDePlanDeEdicion.MAX_INTERVALS
                && activeSpeakerVerified == !intervals.isEmpty(), "Estado de hablante incoherente.");
        exigir(samples != null && !samples.isEmpty() && samples.size() <= 15001
                && Math.abs(samples.size() - durationSeconds * 25) <= 2,
                "Muestreo de evidencia incompleto.");
        for (int i = 0; i < samples.size(); i++) {
            Muestra sample = samples.get(i);
            exigir(sample != null && Double.isFinite(sample.time())
                    && Math.abs(sample.time() - i / 25.0) <= 0.000001
                    && sample.time() < durationSeconds && sample.scene() > 0
                    && sample.faceCount() >= 0 && sample.faceCount() <= 3
                    && probability(sample.voiceProbability()) && probability(sample.activeSpeakerProbability())
                    && Double.isFinite(sample.mouthOpening()) && sample.mouthOpening() >= 0,
                    "Muestra de evidencia inválida.");
            if (sample.box() != null) {
                exigir(sample.box().size() == 4 && sample.box().stream().allMatch(v ->
                        v != null && Double.isFinite(v) && v >= 0 && v <= 1), "Encuadre inválido.");
                var box = sample.box();
                exigir(box.get(2) > 0 && box.get(3) > 0 && box.get(0) + box.get(2) <= 1.000001
                        && box.get(1) + box.get(3) <= 1.000001, "Encuadre fuera de imagen.");
            }
        }
        double previousEnd = 0;
        for (IntervaloHablante interval : intervals) {
            exigir(interval != null && Double.isFinite(interval.start()) && Double.isFinite(interval.end())
                    && interval.start() >= previousEnd && interval.end() <= durationSeconds + 0.000001
                    && interval.end() - interval.start() >= 3 - 0.000001
                    && interval.focusTrackRef() != null
                    && interval.focusTrackRef().matches("[A-Za-z][A-Za-z0-9_-]{0,63}"),
                    "Intervalo de evidencia inválido.");
            int first = (int) Math.round(interval.start() * 25);
            exigir(first >= 0 && first < samples.size()
                    && Math.abs(samples.get(first).time() - interval.start()) <= 0.000001,
                    "Intervalo sin muestras de inicio.");
            int scene = samples.get(first).scene();
            double coveredEnd = interval.start();
            for (int i = first; i < samples.size() && samples.get(i).time() < interval.end() - 0.000001; i++) {
                Muestra sample = samples.get(i);
                exigir(sample.scene() == scene && sample.faceCount() == 1 && sample.box() != null
                        && interval.focusTrackRef().equals(sample.track()) && sample.speech()
                        && sample.mouthMotion() && sample.activeSpeakerProbability() >= 0.90 - 0.000001,
                        "El intervalo cruza una escena, silencio o persona sin evidencia.");
                coveredEnd = Math.min(durationSeconds, sample.time() + 1 / 25.0);
            }
            exigir(coveredEnd >= interval.end() - 0.000001, "Intervalo sin cobertura temporal completa.");
            previousEnd = interval.end();
        }
    }

    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean probability(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
    private static void exigir(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

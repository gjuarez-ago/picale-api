package com.metricol.api.service.media.edicion;

import java.util.List;
import java.util.UUID;

import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

/**
 * Evidencia autoritativa que deberá construir el backend después de cargar el archivo
 * bajo su cuenta. No es un cuerpo HTTP ni una respuesta del planificador generativo.
 * Sin evidencia de hablante activo no se autoriza ningún efecto.
 */
public record ContextoEdicionVideo(UUID workspaceId, UUID sourceAssetId,
        String sourceRevision, String transcriptRevision, double sourceDurationSeconds,
        Tramo sourceRange, boolean alreadyEdited, EvidenciaDeHablante evidence) {

    /** Intervalos en segundos del ORIGINAL; no confundirlos con los eventos de salida. */
    public record EvidenciaDeHablante(String revision, String detectorVersion,
            boolean activeSpeakerVerified, List<IntervaloHablante> intervals) {
        public EvidenciaDeHablante {
            intervals = intervals == null ? null : List.copyOf(intervals);
        }
    }

    /** Un solo presentador seguido dentro del intervalo, sin atravesar cambios de escena. */
    public record IntervaloHablante(double start, double end, String focusTrackRef) { }
}

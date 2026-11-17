package com.metricol.api.service.media.edicion;

import java.util.List;
import java.util.UUID;

/**
 * Receta inmutable: describe una edición, no prueba que haya sido renderizada.
 * Los tiempos de eventos son relativos a la salida; sourceRange pertenece al original.
 * No admite comandos, rutas ni URLs. Todas las plantillas pertenecen al catálogo interno.
 */
public record PlanDeEdicionVideo(
        String schemaVersion,
        UUID workspaceId,
        UUID sourceAssetId,
        String sourceRevision,
        String transcriptRevision,
        String evidenceRevision,
        String policyVersion,
        String templateCatalogVersion,
        PerfilEdicionDinamica profile,
        TimeBase timeBase,
        Tramo sourceRange,
        List<Evento> events) {

    public static final String SCHEMA_VERSION = "talking-video-edit-v1";
    public static final String TEMPLATE_CATALOG_VERSION = "video-templates-v1";

    public PlanDeEdicionVideo {
        events = events == null ? null : List.copyOf(events);
    }

    public enum TimeBase { OUTPUT_SECONDS }

    public record Tramo(double start, double end) {
        public Tramo {
            // La misma receta no debe tener dos identidades por 0.0 y -0.0.
            start = start == 0 ? 0 : start;
            end = end == 0 ? 0 : end;
        }

        public double duration() { return end - start; }
    }

    /** Las composiciones con máscara/teléfono todavía no forman parte de v1. */
    public enum TipoEfecto {
        PUNCH_IN("punch-in-v1", false),
        CARD_INTRO("card-intro-v1", true),
        CAMERA_FRAME("camera-v1", true),
        LETTERBOX_WINDOW("letterbox-v1", true);

        private final String templateVersion;
        private final boolean composition;

        TipoEfecto(String templateVersion, boolean composition) {
            this.templateVersion = templateVersion;
            this.composition = composition;
        }

        public String templateVersion() { return templateVersion; }
        public boolean composition() { return composition; }
    }

    public enum Motivo { OPENING, KEY_STATEMENT, IDEA_CHANGE, CONCLUSION }

    /** scale solo puede superar 1 en PUNCH_IN; cada plantilla controla sus movimientos propios. */
    public record Evento(TipoEfecto type, double start, double end, double scale,
            String templateVersion, String focusTrackRef, Motivo reason) {
        public Evento {
            start = start == 0 ? 0 : start;
            end = end == 0 ? 0 : end;
        }
    }
}

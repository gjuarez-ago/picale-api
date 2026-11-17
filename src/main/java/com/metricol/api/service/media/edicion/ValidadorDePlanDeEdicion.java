package com.metricol.api.service.media.edicion;

import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.metricol.api.service.media.edicion.ContextoEdicionVideo.IntervaloHablante;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Evento;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.TipoEfecto;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

/** Valida la receta contra datos de la cuenta y evidencia independientes del planificador. */
@Component
public class ValidadorDePlanDeEdicion {
    public static final double MAX_SOURCE_SECONDS = 600;
    public static final int MAX_EVENTS = 64;
    public static final int MAX_INTERVALS = 1000;

    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Pattern TRACK = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    // Tolerancia solo aritmética: evita rechazar 20.4 - 20.1 por representación binaria.
    // Es muy inferior a un cuadro y no permite atravesar una escena o pausa real.
    private static final double TIME_EPSILON = 0.000001;

    public void validar(PlanDeEdicionVideo plan, ContextoEdicionVideo contexto) {
        exigir(plan != null && contexto != null, "Falta el plan o su contexto verificado.");
        validarContexto(contexto);
        exigir(PlanDeEdicionVideo.SCHEMA_VERSION.equals(plan.schemaVersion()), "Versión de plan no admitida.");
        exigir(Objects.equals(contexto.workspaceId(), plan.workspaceId())
                && Objects.equals(contexto.sourceAssetId(), plan.sourceAssetId()),
                "El plan no corresponde al video de esta cuenta.");
        exigir(Objects.equals(contexto.sourceRevision(), plan.sourceRevision())
                && Objects.equals(contexto.transcriptRevision(), plan.transcriptRevision())
                && Objects.equals(contexto.evidence().revision(), plan.evidenceRevision()),
                "La fuente, transcripción o evidencia cambió; prepara otro plan.");
        exigir(plan.profile() != null && plan.profile() != PerfilEdicionDinamica.OFF,
                "El plan no tiene un perfil de edición activo.");
        exigir(plan.profile().policyVersion().equals(plan.policyVersion()), "Política editorial no admitida.");
        exigir(PlanDeEdicionVideo.TEMPLATE_CATALOG_VERSION.equals(plan.templateCatalogVersion()),
                "Catálogo de plantillas no admitido.");
        exigir(plan.timeBase() == PlanDeEdicionVideo.TimeBase.OUTPUT_SECONDS,
                "Los eventos deben usar segundos de la salida.");
        exigir(contexto.sourceRange().equals(plan.sourceRange()), "El tramo cambió; prepara otro plan.");
        exigir(plan.events() != null && !plan.events().isEmpty() && plan.events().size() <= MAX_EVENTS,
                "El plan debe contener entre 1 y 64 eventos.");

        Evento anterior = null;
        Evento composicionAnterior = null;
        for (int i = 0; i < plan.events().size(); i++) {
            Evento evento = plan.events().get(i);
            validarEvento(evento, contexto);
            if (anterior != null) {
                exigir(evento.start() + TIME_EPSILON >= anterior.end()
                        && evento.start() - anterior.start() + TIME_EPSILON >= 3,
                        "Los eventos se superponen o están demasiado juntos.");
            }
            if (evento.type().composition()) {
                exigir(composicionAnterior == null
                        || evento.start() - composicionAnterior.start() + TIME_EPSILON >= 8,
                        "Las composiciones están demasiado juntas.");
                composicionAnterior = evento;
            }
            int eventos = 0;
            int composiciones = 0;
            for (int j = i; j >= 0 && evento.start() - plan.events().get(j).start() < 60 - TIME_EPSILON; j--) {
                eventos++;
                if (plan.events().get(j).type().composition()) composiciones++;
            }
            exigir(eventos <= plan.profile().maxEventsPerMinute()
                    && composiciones <= plan.profile().maxCompositionsPerMinute(),
                    "El plan supera la frecuencia del perfil.");
            anterior = evento;
        }
    }

    private void validarContexto(ContextoEdicionVideo contexto) {
        exigir(contexto.workspaceId() != null && contexto.sourceAssetId() != null,
                "Falta la cuenta o el original verificado.");
        exigir(coincide(HASH, contexto.sourceRevision()) && coincide(REVISION, contexto.transcriptRevision()),
                "Falta una revisión válida de fuente o transcripción.");
        exigir(Double.isFinite(contexto.sourceDurationSeconds()) && contexto.sourceDurationSeconds() > 0
                && contexto.sourceDurationSeconds() <= MAX_SOURCE_SECONDS, "Duración de fuente no admitida.");
        exigir(tramoValido(contexto.sourceRange(), contexto.sourceDurationSeconds())
                && contexto.sourceRange().duration() + TIME_EPSILON >= 3, "El tramo de salida no es válido.");
        exigir(!contexto.alreadyEdited(), "El video ya editado requiere revisión antes de añadir efectos.");
        var evidencia = contexto.evidence();
        exigir(evidencia != null && evidencia.activeSpeakerVerified()
                && coincide(REVISION, evidencia.revision()) && coincide(REVISION, evidencia.detectorVersion()),
                "No existe evidencia verificada de una persona hablando.");
        exigir(evidencia.intervals() != null && !evidencia.intervals().isEmpty()
                && evidencia.intervals().size() <= MAX_INTERVALS, "Faltan intervalos de hablante válidos.");
        double finAnterior = -1;
        for (IntervaloHablante intervalo : evidencia.intervals()) {
            exigir(intervalo != null && tramoValido(new Tramo(intervalo.start(), intervalo.end()),
                    contexto.sourceDurationSeconds()) && intervalo.end() - intervalo.start() + TIME_EPSILON >= 3
                    && coincide(TRACK, intervalo.focusTrackRef()), "Intervalo de hablante no válido.");
            exigir(intervalo.start() + TIME_EPSILON >= finAnterior,
                    "Los intervalos de hablante se superponen o no están ordenados.");
            finAnterior = intervalo.end();
        }
    }

    private void validarEvento(Evento evento, ContextoEdicionVideo contexto) {
        exigir(evento != null && evento.type() != null && evento.reason() != null, "Evento incompleto.");
        exigir(tramoValido(new Tramo(evento.start(), evento.end()), contexto.sourceRange().duration())
                && evento.end() - evento.start() + TIME_EPSILON >= 0.3
                && evento.end() - evento.start() <= 3 + TIME_EPSILON,
                "El evento debe durar entre 0.3 y 3 segundos dentro de la salida.");
        exigir(evento.type().templateVersion().equals(evento.templateVersion()), "Plantilla no admitida para el efecto.");
        exigir(Double.isFinite(evento.scale()) && (evento.type() == TipoEfecto.PUNCH_IN
                ? evento.scale() >= 1.01 && evento.scale() <= 1.20 : evento.scale() == 1),
                "La escala no es válida para este efecto.");
        exigir(coincide(TRACK, evento.focusTrackRef()), "La referencia del rostro no es válida.");
        double inicioOriginal = contexto.sourceRange().start() + evento.start();
        double finOriginal = contexto.sourceRange().start() + evento.end();
        boolean contenido = contexto.evidence().intervals().stream().anyMatch(intervalo ->
                intervalo.focusTrackRef().equals(evento.focusTrackRef())
                        && inicioOriginal + TIME_EPSILON >= intervalo.start()
                        && finOriginal <= intervalo.end() + TIME_EPSILON);
        exigir(contenido, "El efecto sale del tramo verificado o sigue a otra persona.");
        if (evento.type() == TipoEfecto.CARD_INTRO) {
            exigir(evento.start() <= 1 && evento.reason() == PlanDeEdicionVideo.Motivo.OPENING,
                    "La tarjeta de apertura debe aparecer al inicio.");
        }
    }

    private static boolean tramoValido(Tramo tramo, double limite) {
        return tramo != null && Double.isFinite(tramo.start()) && Double.isFinite(tramo.end())
                && tramo.start() >= 0 && tramo.end() > tramo.start() && tramo.end() <= limite + TIME_EPSILON;
    }

    private static boolean coincide(Pattern patron, String valor) {
        return valor != null && patron.matcher(valor).matches();
    }

    private static void exigir(boolean cumple, String mensaje) {
        if (!cumple) throw new IllegalArgumentException(mensaje);
    }
}

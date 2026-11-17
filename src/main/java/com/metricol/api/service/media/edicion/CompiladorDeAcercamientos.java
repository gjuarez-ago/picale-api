package com.metricol.api.service.media.edicion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.TipoEfecto;
import com.metricol.api.service.media.edicion.PreparadorDePlanDeEdicion.PlanPreparado;

/** Primer ejecutor visual: exclusivamente PUNCH_IN, con centro estable y margen para la cabeza. */
@Component
public class CompiladorDeAcercamientos {
    public static final String VERSION = "punch-render-v1";
    private static final JsonMapper CANONICO = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    private final ValidadorDePlanDeEdicion validador;

    public CompiladorDeAcercamientos(ValidadorDePlanDeEdicion validador) {
        this.validador = validador;
    }

    public record Movimiento(double start, double end, double scale, double anchorX, double anchorY, double ramp) {
        public double escalaEn(double seconds) {
            return 1 + (scale - 1) * suave((seconds - start) / ramp) * suave((end - seconds) / ramp);
        }
        private static double suave(double x) {
            double u = Math.max(0, Math.min(1, x));
            return u * u * (3 - 2 * u);
        }
    }

    /** Constructor privado: el motor no recibe filtros arbitrarios de un DTO o del modelo. */
    public static final class Composicion {
        private final String filtro;
        private final int ancho, alto;
        private final double inicioFuente, finFuente;
        private final List<Movimiento> movimientos;

        private Composicion(String filtro, int ancho, int alto, double inicioFuente, double finFuente,
                List<Movimiento> movimientos) {
            this.filtro = filtro;
            this.ancho = ancho;
            this.alto = alto;
            this.inicioFuente = inicioFuente;
            this.finFuente = finFuente;
            this.movimientos = List.copyOf(movimientos);
        }
        public String filtro() { return filtro; }
        public int ancho() { return ancho; }
        public int alto() { return alto; }
        public double inicioFuente() { return inicioFuente; }
        public double finFuente() { return finFuente; }
        public List<Movimiento> movimientos() { return movimientos; }
    }

    public Composicion compilar(PlanPreparado preparado, ContextoEdicionVideo contexto,
            InformeDeHablante informe, int ancho, int alto) {
        if (preparado == null || informe == null || contexto == null) {
            throw new IllegalArgumentException("Falta el plan preparado o su evidencia.");
        }
        var plan = preparado.plan();
        validador.validar(plan, contexto);
        try {
            String canonico = CANONICO.writeValueAsString(plan);
            String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonico.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!canonico.equals(preparado.canonicalJson()) || !key.equals(preparado.idempotencyKey())) {
                throw new IllegalArgumentException("La identidad del plan preparado no coincide con su receta.");
            }
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("No se pudo comprobar la identidad del plan.", ex);
        }
        informe.validarContra(contexto.sourceRevision(), contexto.sourceDurationSeconds());
        if (!Objects.equals(plan.evidenceRevision(), informe.evidenceRevision())
                || !Objects.equals(contexto.evidence().detectorVersion(), informe.detectorVersion())
                || !Objects.equals(contexto.evidence().intervals(), informe.intervals())) {
            throw new IllegalArgumentException("La evidencia del encuadre cambió; prepara otro plan.");
        }
        if (ancho < 16 || alto < 16 || ancho % 2 != 0 || alto % 2 != 0
                || (long) ancho * alto > 3840L * 2160) {
            throw new IllegalArgumentException("Resolución no admitida para este render.");
        }
        List<Movimiento> movimientos = new ArrayList<>();
        for (var evento : plan.events()) {
            if (evento.type() != TipoEfecto.PUNCH_IN) {
                throw new IllegalArgumentException("Este ejecutor solo admite acercamientos; no se omiten otros efectos.");
            }
            double start = plan.sourceRange().start() + evento.start();
            double end = plan.sourceRange().start() + evento.end();
            var samples = informe.samples().stream().filter(s -> s.time() >= start - 0.000001
                    && s.time() < end - 0.000001).toList();
            if (samples.isEmpty() || samples.stream().anyMatch(s -> s.box() == null
                    || !evento.focusTrackRef().equals(s.track()))) {
                throw new IllegalArgumentException("El acercamiento no tiene seguimiento de rostro suficiente.");
            }
            double x = mediana(samples.stream().map(s -> s.box().get(0) + s.box().get(2) / 2).toList());
            double y = mediana(samples.stream().map(s -> s.box().get(1) + s.box().get(3) / 2).toList());
            // El escalado se redondea a píxeles pares. Validar la geometría efectiva, no solo la ideal.
            double scaleX = Math.ceil(ancho * evento.scale() / 2) * 2 / ancho;
            double scaleY = Math.ceil(alto * scaleX / 2) * 2 / alto;
            double left = x * (1 - 1 / scaleX), top = y * (1 - 1 / scaleY);
            double right = left + 1 / scaleX, bottom = top + 1 / scaleY;
            for (var sample : samples) {
                var box = sample.box();
                double protectedLeft = Math.max(0, box.get(0) - box.get(2) * .12);
                double protectedTop = Math.max(0, box.get(1) - box.get(3) * .35);
                double protectedRight = Math.min(1, box.get(0) + box.get(2) * 1.12);
                double protectedBottom = Math.min(1, box.get(1) + box.get(3) * 1.25);
                if (protectedLeft < left + .015 || protectedTop < top + .015
                        || protectedRight > right - .015 || protectedBottom > bottom - .015) {
                    throw new IllegalArgumentException("No hay margen suficiente para acercarse sin cortar la cabeza.");
                }
            }
            movimientos.add(new Movimiento(evento.start(), evento.end(), evento.scale(), x, y,
                    Math.min(.3, (evento.end() - evento.start()) / 2)));
        }
        StringBuilder zoom = new StringBuilder("1");
        StringBuilder anchorX = new StringBuilder("0.5"), anchorY = new StringBuilder("0.5");
        for (var m : movimientos) {
            zoom.append("+").append(numero(m.scale() - 1)).append("*")
                    .append(suave("(t-" + numero(m.start()) + ")/" + numero(m.ramp())))
                    .append("*").append(suave("(" + numero(m.end()) + "-t)/" + numero(m.ramp())));
            String active = "*between(t," + numero(m.start()) + "," + numero(m.end()) + ")";
            anchorX.append("+").append(numero(m.anchorX() - .5)).append(active);
            anchorY.append("+").append(numero(m.anchorY() - .5)).append(active);
        }
        // t es PTS en segundos: no convertir una fuente VFR en on/fps ni alterar su voz.
        String filtro = "scale=w='ceil(iw*(" + zoom + ")/2)*2':h='ceil(ih*ow/iw/2)*2':eval=frame:flags=lanczos,"
                + "crop=w=" + ancho + ":h=" + alto + ":x='(iw-ow)*(" + anchorX + ")':y='(ih-oh)*(" + anchorY
                + ")',setsar=1";
        if (filtro.length() > 24000) throw new IllegalArgumentException("El plan excede la complejidad de este ejecutor.");
        return new Composicion(filtro, ancho, alto, plan.sourceRange().start(), plan.sourceRange().end(), movimientos);
    }

    private static double mediana(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2 : sorted.get(middle);
    }

    private static String suave(String expression) {
        String u = "clip(" + expression + ",0,1)";
        return "(" + u + "*" + u + "*(3-2*" + u + "))";
    }

    private static String numero(double value) { return String.format(Locale.ROOT, "%.9f", value); }
}

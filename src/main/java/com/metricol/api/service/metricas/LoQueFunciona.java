package com.metricol.api.service.metricas;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.PostTarget;
import com.metricol.api.repository.PostTargetRepository;

/**
 * Lo que le funciona a cada cuenta, sacado de cómo le fue a lo que publicó en
 * los últimos 90 días. Lo usan el calendario del agente (a qué hora) y quien
 * escribe los textos (qué hashtags), y se enseña en la pantalla del agente.
 *
 * <p>Se recalcula como mucho cada media hora por cuenta: son cientos de filas
 * como mucho, pero el agente lo pide por cada propuesta.
 */
@Service
public class LoQueFunciona {

    private static final Duration VIGENCIA = Duration.ofMinutes(30);
    private static final int DIAS = 90;

    /** Una publicación que rindió, para enseñarla. */
    public record Destacada(String red, String texto, String enlace, LocalDateTime cuando, Long vistas, Long meGusta,
            Long comentarios, Long compartidos, Long guardados) {
    }

    /** Lo que ve la persona en la pantalla del agente. */
    public record Resumen(int medidas, int minimo, List<String> horas, List<String> hashtagsBuenos,
            List<String> hashtagsFlojos, List<Destacada> mejores) {
    }

    private record Calculo(AprendizajeDeRendimiento.Aprendido aprendido, List<Destacada> mejores, Instant cuando) {
    }

    private final PostTargetRepository destinos;
    private final Map<UUID, Calculo> cache = new ConcurrentHashMap<>();

    public LoQueFunciona(PostTargetRepository destinos) {
        this.destinos = destinos;
    }

    public AprendizajeDeRendimiento.Aprendido de(UUID workspaceId) {
        return calcular(workspaceId).aprendido();
    }

    public Resumen resumen(UUID workspaceId) {
        Calculo c = calcular(workspaceId);
        AprendizajeDeRendimiento.Aprendido a = c.aprendido();
        return new Resumen(a.medidas(), AprendizajeDeRendimiento.MIN_MUESTRAS,
                a.horas().stream().limit(3).map(h -> String.format("%02d:00", h.getHour())).toList(),
                a.hashtagsBuenos(), a.hashtagsFlojos(), c.mejores());
    }

    /** Hay métricas nuevas: lo calculado ya no vale. */
    public void olvidar() {
        cache.clear();
    }

    private Calculo calcular(UUID workspaceId) {
        if (workspaceId == null) {
            return new Calculo(AprendizajeDeRendimiento.Aprendido.NADA, List.of(), Instant.now());
        }
        Calculo guardado = cache.get(workspaceId);
        if (guardado != null && Duration.between(guardado.cuando(), Instant.now()).compareTo(VIGENCIA) < 0) {
            return guardado;
        }
        List<AprendizajeDeRendimiento.Muestra> muestras = new ArrayList<>();
        List<Map.Entry<Double, Destacada>> conPuntaje = new ArrayList<>();
        for (Object[] f : destinos.medidasDe(workspaceId.toString(), LocalDateTime.now().minusDays(DIAS))) {
            LocalDateTime cuando = fecha(f[0]);
            String texto = f[1] == null ? "" : String.valueOf(f[1]);
            PostTarget medido = PostTarget.builder()
                    .vistas(largo(f[2])).alcance(largo(f[3])).meGusta(largo(f[4])).comentarios(largo(f[5]))
                    .compartidos(largo(f[6])).guardados(largo(f[7])).metricasEn(LocalDateTime.now()).build();
            Double puntaje = medido.puntaje();
            if (cuando == null || puntaje == null) {
                continue;
            }
            muestras.add(new AprendizajeDeRendimiento.Muestra(cuando, texto, puntaje));
            conPuntaje.add(Map.entry(puntaje, new Destacada(String.valueOf(f[9]), recortar(texto),
                    f[8] == null ? null : String.valueOf(f[8]), cuando, medido.getVistas(), medido.getMeGusta(),
                    medido.getComentarios(), medido.getCompartidos(), medido.getGuardados())));
        }
        conPuntaje.sort(Map.Entry.<Double, Destacada>comparingByKey(Comparator.reverseOrder()));
        Calculo nuevo = new Calculo(AprendizajeDeRendimiento.de(muestras),
                conPuntaje.stream().limit(3).map(Map.Entry::getValue).toList(), Instant.now());
        cache.put(workspaceId, nuevo);
        return nuevo;
    }

    private static String recortar(String texto) {
        String t = texto.strip().replaceAll("\\s+", " ");
        return t.length() <= 140 ? t : t.substring(0, 137) + "…";
    }

    private static Long largo(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static LocalDateTime fecha(Object v) {
        if (v instanceof LocalDateTime l) {
            return l;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return null;
    }

    /** Para quien escribe los textos: los hashtags, en una línea, o nulo si no hay nada que decir. */
    public static String paraElRedactor(AprendizajeDeRendimiento.Aprendido a) {
        if (a == null || !a.suficiente() || (a.hashtagsBuenos().isEmpty() && a.hashtagsFlojos().isEmpty())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!a.hashtagsBuenos().isEmpty()) {
            sb.append("Hashtags que le han funcionado (usa los que vengan al caso): ")
                    .append(String.join(" ", a.hashtagsBuenos())).append(". ");
        }
        if (!a.hashtagsFlojos().isEmpty()) {
            sb.append("Hashtags que no le han funcionado (evitalos): ")
                    .append(String.join(" ", a.hashtagsFlojos())).append('.');
        }
        return sb.toString().strip();
    }

    /** ¿Esta hora es de las que mejor le funcionan a la cuenta? */
    public static boolean esBuenaHora(AprendizajeDeRendimiento.Aprendido a, LocalTime hora) {
        return a != null && a.suficiente() && hora != null
                && a.horas().stream().limit(3).anyMatch(h -> h.getHour() == hora.getHour());
    }
}

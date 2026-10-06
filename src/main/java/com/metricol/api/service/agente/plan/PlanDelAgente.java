package com.metricol.api.service.agente.plan;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.RasgoDelNegocio;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.agente.AgenteService;
import com.metricol.api.service.agente.CalendarioDelAgente;
import com.metricol.api.service.avisos.AvisosPush;

/**
 * Lo que el asistente hace por su cuenta, sin que nadie se lo pida, para que
 * la cuenta nunca se quede callada. Corre en cada vuelta del asistente de
 * fondo, después de revisar lo que se subió.
 *
 * <ol>
 * <li><b>Fechas</b>: de 2 a 7 días antes de una fecha que le toca al
 * negocio ({@link FechasDelAnio}), diseña la pieza para ese día. Sin
 * créditos, le pide al dueño una foto para la fecha.</li>
 * <li><b>Fotos de la semana</b>: cada siete días le manda al teléfono tres
 * fotos concretas que necesita ({@link ListaDeTomas}).</li>
 * <li><b>Sin silencio</b>: si no hay nada propuesto ni programado en los
 * próximos días y no hay fotos por revisar, diseña una pieza de su oficio
 * (un consejo, un dato). Una por semana como mucho.</li>
 * </ol>
 *
 * <p>Solo en horas de oficina (los avisos no despiertan a nadie) y una acción
 * de diseño por vuelta.
 */
@Component
public class PlanDelAgente {

    private static final Logger log = LoggerFactory.getLogger(PlanDelAgente.class);

    /** Lo más lejos que se diseña una fecha: más antes, la persona la olvida. */
    static final int DIAS_ANTES_MAX = 7;
    /** Lo más cerca: menos, no da tiempo de aprobarla. */
    static final int DIAS_ANTES_MIN = 2;
    /** Sin nada en estos días, la cuenta se quedaría callada. */
    static final int DIAS_DE_SILENCIO = 4;

    private final AgenteService agente;
    private final ListaDeTomas tomas;
    private final AvisosPush avisos;
    private final WorkspaceRepository workspaces;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanDelAgente(AgenteService agente, ListaDeTomas tomas, AvisosPush avisos, WorkspaceRepository workspaces) {
        this.agente = agente;
        this.tomas = tomas;
        this.avisos = avisos;
        this.workspaces = workspaces;
    }

    /** Lo que se ve del plan: las fechas que vienen y las fotos que pidió. */
    public record Plan(List<FechaVista> fechas, List<ListaDeTomas.Toma> tomas, LocalDateTime tomasEn) {
    }

    /**
     * @param estado LISTA (ya la preparó o la pidió), PRONTO (la prepara en los días antes)
     */
    public record FechaVista(String nombre, LocalDate dia, String idea, String estado) {
    }

    public Plan plan(UUID workspaceId) {
        Workspace w = workspaces.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Espacio no encontrado."));
        return plan(w, LocalDate.now());
    }

    public Plan plan(Workspace w, LocalDate hoy) {
        Set<String> hechas = hechas(w);
        List<FechaVista> fechas = new ArrayList<>();
        for (FechasDelAnio.Proxima p : proximas(w, hoy, 45)) {
            fechas.add(new FechaVista(p.fecha().nombre(), p.dia(), p.fecha().idea(),
                    hechas.contains(p.clave()) ? "LISTA" : "PRONTO"));
        }
        return new Plan(fechas, leerTomas(w), w.getAgenteTomasEn());
    }

    /** Una vuelta del plan para un espacio. Nunca lanza. */
    public void trabajar(UUID workspaceId, LocalDateTime ahora) {
        try {
            Workspace w = workspaces.findById(workspaceId).orElse(null);
            if (w == null || !w.conAgente() || w.getAgenteDesde() == null || !deOficina(ahora)
                    || !agente.conRedes()) {
                return;
            }
            pedirFotos(w, ahora);
            if (!atenderFecha(w, ahora)) {
                romperSilencio(w, ahora);
            }
        } catch (RuntimeException ex) {
            log.warn("El plan del asistente falló en {}: {}", workspaceId, ex.toString());
        }
    }

    static boolean deOficina(LocalDateTime ahora) {
        return ahora.getHour() >= 9 && ahora.getHour() < 20;
    }

    // ------------------------------------------------------------ 1. fechas

    /** @return si hizo algo de diseño en esta vuelta (una por vuelta) */
    boolean atenderFecha(Workspace w, LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        Set<String> hechas = hechas(w);
        for (FechasDelAnio.Proxima p : proximas(w, hoy, DIAS_ANTES_MAX)) {
            if (hechas.contains(p.clave()) || p.dia().isBefore(hoy.plusDays(DIAS_ANTES_MIN))) {
                continue;
            }
            LocalDateTime cuando = p.dia().atTime(LocalTime.of(10, 0));
            String encargo = "Publicacion para " + p.fecha().nombre() + " (" + dia(p.dia()) + "): "
                    + p.fecha().idea();
            AgenteService.SinFoto r = agente.proponerSinFoto(w.getId(), encargo,
                    "Se viene " + p.fecha().nombre() + " (" + dia(p.dia()) + ") y le toca a tu negocio: la preparé "
                            + "para ese día.",
                    CalendarioDelAgente.Categoria.COMUNIDAD, cuando);
            switch (r == null ? AgenteService.SinFoto.NO_SALIO : r) {
                case PROPUESTA -> {
                    marcar(w, p.clave());
                    log.info("Fecha {} preparada para {}", p.clave(), w.getId());
                    return true;
                }
                case SIN_CREDITOS -> {
                    avisos.avisarAlEquipo(w.getId(), p.fecha().nombre() + " es el " + dia(p.dia()),
                            "Mándame una foto de tu negocio para esa fecha y la preparo. "
                                    + p.fecha().idea(), Map.of("tipo", "fecha"));
                    marcar(w, p.clave());
                    return false;
                }
                default -> {
                    // No salió (la IA, el tope del día): la siguiente vuelta lo intenta otra vez.
                    return false;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 2. fotos de la semana

    void pedirFotos(Workspace w, LocalDateTime ahora) {
        if (w.getAgenteTomasEn() != null && w.getAgenteTomasEn().isAfter(ahora.minusDays(7))) {
            return;
        }
        List<ListaDeTomas.Toma> lista = tomas.semana(w, proximas(w, ahora.toLocalDate(), 21));
        try {
            w.setAgenteTomas(mapper.writeValueAsString(lista));
        } catch (Exception ex) {
            return;
        }
        w.setAgenteTomasEn(ahora);
        workspaces.save(w);
        StringBuilder cuerpo = new StringBuilder("Esta semana me ayudarían: ");
        for (int i = 0; i < lista.size(); i++) {
            cuerpo.append(i + 1).append(") ").append(lista.get(i).que()).append(i < lista.size() - 1 ? "; " : ".");
        }
        avisos.avisarAlEquipo(w.getId(), "Fotos para esta semana", cuerpo.toString(), Map.of("tipo", "tomas"));
    }

    List<ListaDeTomas.Toma> leerTomas(Workspace w) {
        if (w.getAgenteTomas() == null) {
            return List.of();
        }
        try {
            return mapper.readValue(w.getAgenteTomas(), new TypeReference<List<ListaDeTomas.Toma>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    // ------------------------------------------------------------ 3. sin silencio

    void romperSilencio(Workspace w, LocalDateTime ahora) {
        if (w.getAgenteUltimoRelleno() != null && w.getAgenteUltimoRelleno().isAfter(ahora.minusDays(7))) {
            return;
        }
        if (agente.conMaterialPendiente(w) || !agente.callada(DIAS_DE_SILENCIO)) {
            return;
        }
        Set<RasgoDelNegocio> rasgos = w.rasgos();
        String tema = rasgos != null && rasgos.contains(RasgoDelNegocio.EDUCA)
                ? "un consejo practico de su oficio que le sirva a sus clientes"
                : rasgos != null && rasgos.contains(RasgoDelNegocio.POR_PROYECTO)
                        ? "por que elegirlos para un proyecto: su experiencia y como trabajan"
                        : "algo util o interesante de su giro para sus clientes";
        AgenteService.SinFoto r = agente.proponerSinFoto(w.getId(),
                "Publicacion para que la cuenta no se quede callada: " + tema + ".",
                "Tu cuenta se iba a quedar sin publicaciones estos días: preparé una pieza de tu oficio.",
                CalendarioDelAgente.Categoria.COMUNIDAD, null);
        if (r != null && r != AgenteService.SinFoto.NO_SALIO) {
            // Sin créditos también cuenta: la lista de fotos de la semana ya le pide material.
            w.setAgenteUltimoRelleno(ahora);
            workspaces.save(w);
        }
    }

    // ------------------------------------------------------------ apoyo

    private static List<FechasDelAnio.Proxima> proximas(Workspace w, LocalDate hoy, int dias) {
        return FechasDelAnio.proximas(hoy, dias, w.getGiro(), w.getDescripcion(), w.rasgos());
    }

    static Set<String> hechas(Workspace w) {
        if (w.getAgenteFechasHechas() == null || w.getAgenteFechasHechas().isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(w.getAgenteFechasHechas().split(",")));
    }

    private void marcar(Workspace w, String clave) {
        Set<String> hechas = new LinkedHashSet<>(hechas(w));
        hechas.add(clave);
        // Solo las del año en curso y el siguiente: lo viejo no hace falta recordarlo.
        int anio = LocalDate.now().getYear();
        hechas.removeIf(h -> !h.endsWith("-" + anio) && !h.endsWith("-" + (anio + 1)));
        w.setAgenteFechasHechas(String.join(",", hechas));
        workspaces.save(w);
    }

    static String dia(LocalDate d) {
        return d.getDayOfMonth() + " de " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-MX"));
    }
}

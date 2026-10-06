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
 * negocio ({@link FechasDelAnio}), le pregunta al dueño si le prepara la
 * pieza. Diseñar cuesta créditos: se hace solo si dice que sí
 * ({@link #prepararFecha}).</li>
 * <li><b>Fotos de la semana</b>: cada siete días le manda al teléfono tres
 * fotos concretas que necesita ({@link ListaDeTomas}).</li>
 * <li><b>Sin silencio</b>: si no hay nada propuesto ni programado en los
 * próximos días y no hay fotos por revisar, ofrece una pieza de su oficio
 * (un consejo, un dato) y la hace si se la piden. Una oferta por semana.</li>
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
    private final com.metricol.api.service.agente.PresupuestoDelAsistente presupuesto;
    private final com.metricol.api.service.agente.TrabajoDeFondo fondo;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanDelAgente(AgenteService agente, ListaDeTomas tomas, AvisosPush avisos, WorkspaceRepository workspaces,
            com.metricol.api.service.agente.PresupuestoDelAsistente presupuesto,
            com.metricol.api.service.agente.TrabajoDeFondo fondo) {
        this.agente = agente;
        this.tomas = tomas;
        this.avisos = avisos;
        this.workspaces = workspaces;
        this.presupuesto = presupuesto;
        this.fondo = fondo;
    }

    /**
     * Lo que se ve del plan.
     *
     * @param relleno  la cuenta se va a quedar callada: ofrece una pieza de su oficio
     * @param presupuesto cuánto le queda al asistente este mes
     */
    public record Plan(List<FechaVista> fechas, List<ListaDeTomas.Toma> tomas, LocalDateTime tomasEn,
            boolean relleno, com.metricol.api.service.agente.PresupuestoDelAsistente.Estado presupuesto,
            Pedido pedido) {
    }

    /**
     * Lo último que la persona pidió preparar y cómo va, para que se vea:
     * PREPARANDO (tarda uno o dos minutos), LISTA (está en sus propuestas),
     * SIN_CREDITOS o NO_SALIO. Nulo si no pidió nada en el último día.
     */
    public record Pedido(String que, String estado, LocalDateTime en) {
    }

    /** Si se quedó "preparando" más de esto, algo se cayó: se dice que no salió. */
    static final int MINUTOS_PARA_RENDIRSE = 10;

    /**
     * @param clave  para pedir que la prepare ("MADRES-2026")
     * @param estado PREPARADA (ya la pidió), OFRECIDA (le preguntó), PRONTO (le preguntará unos días antes)
     */
    public record FechaVista(String clave, String nombre, LocalDate dia, String idea, String estado) {
    }

    public Plan plan(UUID workspaceId) {
        Workspace w = workspaces.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Espacio no encontrado."));
        return plan(w, LocalDate.now());
    }

    public Plan plan(Workspace w, LocalDate hoy) {
        Set<String> ofrecidas = hechas(w);
        Set<String> preparadas = lista(w.getAgenteFechasPreparadas());
        List<FechaVista> fechas = new ArrayList<>();
        for (FechasDelAnio.Proxima p : proximas(w, hoy, 45)) {
            fechas.add(new FechaVista(p.clave(), p.fecha().nombre(), p.dia(), p.fecha().idea(),
                    preparadas.contains(p.clave()) ? "PREPARADA" : ofrecidas.contains(p.clave()) ? "OFRECIDA" : "PRONTO"));
        }
        boolean relleno = w.conAgente() && w.getAgenteUltimoRelleno() != null
                && w.getAgenteUltimoRelleno().isAfter(LocalDateTime.now().minusDays(7))
                && agente.callada(DIAS_DE_SILENCIO);
        return new Plan(fechas, leerTomas(w), w.getAgenteTomasEn(), relleno, presupuesto.estado(w.getId()),
                pedido(w, LocalDateTime.now()));
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

    /** Pregunta por la fecha más cercana que no se haya ofrecido. @return si ofreció algo */
    boolean atenderFecha(Workspace w, LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        Set<String> ofrecidas = hechas(w);
        for (FechasDelAnio.Proxima p : proximas(w, hoy, DIAS_ANTES_MAX)) {
            if (ofrecidas.contains(p.clave()) || p.dia().isBefore(hoy.plusDays(DIAS_ANTES_MIN))) {
                continue;
            }
            avisos.avisarAlEquipo(w.getId(), p.fecha().nombre() + " es el " + dia(p.dia()),
                    "¿Te preparo la publicación para ese día? Tócale «Prepárala» en Hoy, o mándame una foto tuya.",
                    Map.of("tipo", "fecha", "clave", p.clave()));
            marcar(w, p.clave());
            return true;
        }
        return false;
    }

    /**
     * El dueño dijo que sí: diseña la pieza para la fecha, en segundo plano, y
     * avisa cuando está. Cuesta créditos y entra en el presupuesto.
     *
     * @throws IllegalStateException si la fecha no le toca o ya no alcanza el presupuesto
     */
    public void prepararFecha(UUID workspaceId, String clave) {
        Workspace w = workspaces.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Espacio no encontrado."));
        LocalDate hoy = LocalDate.now();
        FechasDelAnio.Proxima p = proximas(w, hoy, 45).stream().filter(x -> x.clave().equals(clave)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Esa fecha ya pasó o no le toca a tu negocio."));
        if (lista(w.getAgenteFechasPreparadas()).contains(clave)) {
            throw new IllegalStateException("Esa ya la preparé: está en tus propuestas.");
        }
        exigirPresupuesto(workspaceId);
        agregar(w, clave);
        LocalDateTime cuando = p.dia().atTime(LocalTime.of(10, 0));
        if (cuando.isBefore(LocalDateTime.now().plusHours(3))) {
            cuando = null;
        }
        LocalDateTime para = cuando;
        anotarPedido(workspaceId, "la publicación de " + p.fecha().nombre(), "PREPARANDO");
        fondo.enEspacio(workspaceId, () -> {
            AgenteService.SinFoto r = agente.proponerSinFoto(workspaceId,
                    "Publicacion para " + p.fecha().nombre() + " (" + dia(p.dia()) + "): " + p.fecha().idea(),
                    "Me pediste la pieza para " + p.fecha().nombre() + " (" + dia(p.dia()) + ").",
                    CalendarioDelAgente.Categoria.COMUNIDAD, para);
            avisarResultado(workspaceId, r, p.fecha().nombre());
            if (r != AgenteService.SinFoto.PROPUESTA) {
                quitar(workspaceId, clave);
            }
        });
    }

    /** El dueño quiere la pieza de su oficio para que la cuenta no se calle. */
    public void prepararRelleno(UUID workspaceId) {
        Workspace w = workspaces.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Espacio no encontrado."));
        exigirPresupuesto(workspaceId);
        String tema = tema(w.rasgos());
        anotarPedido(workspaceId, "una pieza de tu oficio", "PREPARANDO");
        fondo.enEspacio(workspaceId, () -> avisarResultado(workspaceId, agente.proponerSinFoto(workspaceId,
                "Publicacion para que la cuenta no se quede callada: " + tema + ".",
                "Me pediste una pieza de tu oficio para que tu cuenta no se quede sin publicaciones.",
                CalendarioDelAgente.Categoria.COMUNIDAD, null), "tu pieza de la semana"));
    }

    static Pedido pedido(Workspace w, LocalDateTime ahora) {
        if (w.getAgentePiezaEstado() == null || w.getAgentePiezaEn() == null
                || w.getAgentePiezaEn().isBefore(ahora.minusDays(1))) {
            return null;
        }
        String estado = w.getAgentePiezaEstado();
        if ("PREPARANDO".equals(estado) && w.getAgentePiezaEn().isBefore(ahora.minusMinutes(MINUTOS_PARA_RENDIRSE))) {
            estado = "NO_SALIO";
        }
        return new Pedido(w.getAgentePiezaQue(), estado, w.getAgentePiezaEn());
    }

    private void anotarPedido(UUID workspaceId, String que, String estado) {
        workspaces.findById(workspaceId).ifPresent(w -> {
            if (que != null) {
                w.setAgentePiezaQue(que.length() <= 120 ? que : que.substring(0, 120));
            }
            w.setAgentePiezaEstado(estado);
            w.setAgentePiezaEn(LocalDateTime.now());
            workspaces.save(w);
        });
    }

    private void exigirPresupuesto(UUID workspaceId) {
        if (!presupuesto.alcanza(workspaceId)) {
            throw new IllegalStateException(
                    "Llegaste al presupuesto del asistente de este mes. Puedes subirlo en Asistente.");
        }
    }

    private void avisarResultado(UUID workspaceId, AgenteService.SinFoto r, String que) {
        AgenteService.SinFoto resultado = r == null ? AgenteService.SinFoto.NO_SALIO : r;
        anotarPedido(workspaceId, null, switch (resultado) {
            case PROPUESTA -> "LISTA";
            case SIN_CREDITOS -> "SIN_CREDITOS";
            default -> "NO_SALIO";
        });
        switch (resultado) {
            case PROPUESTA -> avisos.avisarAlEquipo(workspaceId, "Lista: " + que,
                    "Ya está en tus propuestas. Revísala y apruébala cuando quieras.", Map.of("tipo", "nuevas"));
            case SIN_CREDITOS -> avisos.avisarAlEquipo(workspaceId, "No alcanzaron los créditos",
                    "No pude preparar " + que + ": se acabaron los créditos de la semana o el presupuesto del mes.",
                    Map.of("tipo", "nuevas"));
            default -> avisos.avisarAlEquipo(workspaceId, "No salió " + que,
                    "No pude prepararla ahora. Inténtalo de nuevo en un rato.", Map.of("tipo", "nuevas"));
        }
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
        avisos.avisarAlEquipo(w.getId(), "Tu cuenta se va a quedar callada",
                "No hay publicaciones para los próximos días. ¿Te preparo una pieza de tu oficio? Tócale «Prepárala» "
                        + "en Hoy, o mándame fotos.", Map.of("tipo", "relleno"));
        w.setAgenteUltimoRelleno(ahora);
        workspaces.save(w);
    }

    static String tema(Set<RasgoDelNegocio> rasgos) {
        return rasgos != null && rasgos.contains(RasgoDelNegocio.EDUCA)
                ? "un consejo practico de su oficio que le sirva a sus clientes"
                : rasgos != null && rasgos.contains(RasgoDelNegocio.POR_PROYECTO)
                        ? "por que elegirlos para un proyecto: su experiencia y como trabajan"
                        : "algo util o interesante de su giro para sus clientes";
    }

    // ------------------------------------------------------------ apoyo

    private static List<FechasDelAnio.Proxima> proximas(Workspace w, LocalDate hoy, int dias) {
        return FechasDelAnio.proximas(hoy, dias, w.getGiro(), w.getDescripcion(), w.rasgos());
    }

    static Set<String> hechas(Workspace w) {
        return lista(w.getAgenteFechasHechas());
    }

    static Set<String> lista(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    private void agregar(Workspace w, String clave) {
        Set<String> todas = new LinkedHashSet<>(lista(w.getAgenteFechasPreparadas()));
        todas.add(clave);
        w.setAgenteFechasPreparadas(String.join(",", todas));
        workspaces.save(w);
    }

    private void quitar(UUID workspaceId, String clave) {
        workspaces.findById(workspaceId).ifPresent(w -> {
            Set<String> todas = new LinkedHashSet<>(lista(w.getAgenteFechasPreparadas()));
            todas.remove(clave);
            w.setAgenteFechasPreparadas(String.join(",", todas));
            workspaces.save(w);
        });
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

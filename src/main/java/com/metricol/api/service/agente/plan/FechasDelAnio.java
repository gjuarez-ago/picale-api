package com.metricol.api.service.agente.plan;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

import com.metricol.api.enums.RasgoDelNegocio;

/**
 * Las fechas del año que un negocio en México puede aprovechar en redes, y a
 * quién le tocan. Las de todos (Día de las Madres, Navidad) le tocan a
 * cualquiera; las de temporada o de compra (Buen Fin, San Valentín), a quien
 * vende producto o tiene local; las de oficio (Día del Albañil, del Médico),
 * a quien es de ese oficio por su giro.
 *
 * <p>Sin estado ni base de datos: para el mismo giro, rasgos y día, siempre las
 * mismas fechas.
 */
public final class FechasDelAnio {

    /**
     * Una fecha del calendario.
     *
     * @param codigo     para recordar que ya se atendió ("MADRES")
     * @param nombre     cómo se le dice ("Día de las Madres")
     * @param dia        la fecha en un año dado
     * @param paraTodos  le toca a cualquier negocio
     * @param rasgos     le toca a quien tenga alguno de estos rasgos
     * @param giros      le toca a quien tenga en su giro o descripción una de estas palabras
     * @param idea       qué publicar, como encargo para quien diseña y escribe
     */
    public record Fecha(String codigo, String nombre, IntFunction<LocalDate> dia, boolean paraTodos,
            Set<RasgoDelNegocio> rasgos, List<String> giros, String idea) {

        boolean leToca(String texto, Set<RasgoDelNegocio> suyos) {
            if (paraTodos) {
                return true;
            }
            if (suyos != null && rasgos.stream().anyMatch(suyos::contains)) {
                return true;
            }
            return giros.stream().anyMatch(texto::contains);
        }
    }

    /** Una fecha que viene, ya con su día. */
    public record Proxima(Fecha fecha, LocalDate dia) {

        public String clave() {
            return fecha.codigo() + "-" + dia.getYear();
        }
    }

    private static final Set<RasgoDelNegocio> VENDE = Set.of(RasgoDelNegocio.PRODUCTO, RasgoDelNegocio.LOCAL);
    private static final List<String> CONSTRUCCION = List.of("constru", "albanil", "obra", "remodela",
            "arquitect", "ingenier", "impermeab", "acabados", "herreria", "mantenimiento industrial");

    static final List<Fecha> TODAS = List.of(
            new Fecha("REYES", "Día de Reyes", a -> LocalDate.of(a, 1, 6), false, VENDE,
                    List.of("panaderia", "pasteleria", "juguet", "cafeteria", "restaurante"),
                    "Celebra el Día de Reyes con lo que vende el negocio: la rosca, un regalo, una mesa compartida."),
            new Fecha("ENFERMERA", "Día de la Enfermera", a -> LocalDate.of(a, 1, 6), false, Set.of(),
                    List.of("enfermer", "clinica", "hospital", "consultorio"),
                    "Reconoce a las enfermeras: su trabajo diario y su vocacion."),
            new Fecha("ODONTOLOGO", "Día del Odontólogo", a -> LocalDate.of(a, 2, 9), false, Set.of(),
                    List.of("dental", "dentist", "odontolog", "ortodonc"),
                    "Celebra el Día del Odontólogo con un dato de salud dental o un agradecimiento a los pacientes."),
            new Fecha("SAN_VALENTIN", "Día del Amor y la Amistad", a -> LocalDate.of(a, 2, 14), false, VENDE,
                    List.of("flor", "regalo", "joyer", "restaurante", "estetica", "spa", "chocolat", "pasteler"),
                    "Invita a celebrar el amor y la amistad con lo que vende el negocio."),
            new Fecha("MUJER", "Día Internacional de la Mujer", a -> LocalDate.of(a, 3, 8), true, Set.of(), List.of(),
                    "Reconoce a las mujeres del equipo o de la comunidad del negocio, con respeto y sin promocion."),
            new Fecha("PRIMAVERA", "Llegada de la primavera", a -> LocalDate.of(a, 3, 21), false,
                    Set.of(RasgoDelNegocio.TEMPORADA), List.of("jardin", "vivero", "flor", "ropa", "helad", "nieve"),
                    "Da la bienvenida a la primavera con lo que el negocio ofrece en esta temporada."),
            new Fecha("NINO", "Día del Niño", a -> LocalDate.of(a, 4, 30), false, VENDE,
                    List.of("juguet", "escuela", "colegio", "pediatr", "restaurante", "helad", "fiestas"),
                    "Celebra a los niños con lo que el negocio les ofrece."),
            new Fecha("ALBANIL", "Día de la Santa Cruz (Día del Albañil)", a -> LocalDate.of(a, 5, 3), false,
                    Set.of(), CONSTRUCCION,
                    "Reconoce a los albañiles y al equipo de obra en el Día de la Santa Cruz: su trabajo y su orgullo."),
            new Fecha("MADRES", "Día de las Madres", a -> LocalDate.of(a, 5, 10), true, Set.of(), List.of(),
                    "Felicita a las mamás de la comunidad; si el negocio vende algo para regalar, invitalo con calidez."),
            new Fecha("MAESTRO", "Día del Maestro", a -> LocalDate.of(a, 5, 15), false, Set.of(),
                    List.of("escuela", "colegio", "academia", "curso", "educa", "idioma", "papeler"),
                    "Reconoce a los maestros y su labor."),
            new Fecha("CONTADOR", "Día del Contador", a -> LocalDate.of(a, 5, 25), false, Set.of(),
                    List.of("contad", "contab", "fiscal", "despacho"),
                    "Celebra el Día del Contador con un dato fiscal util o un agradecimiento a los clientes."),
            new Fecha("LLUVIAS", "Temporada de lluvias", a -> LocalDate.of(a, 6, 1), false, Set.of(),
                    List.of("impermeab", "techo", "constru", "remodela", "jardin", "plomer", "mantenimiento"),
                    "Ayuda a preparar casas y negocios para la temporada de lluvias con un consejo practico del oficio."),
            new Fecha("PADRE", "Día del Padre",
                    a -> LocalDate.of(a, 6, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.SUNDAY)), true,
                    Set.of(), List.of(),
                    "Felicita a los papás de la comunidad; si el negocio vende algo para regalar, invitalo con calidez."),
            new Fecha("INGENIERO", "Día del Ingeniero", a -> LocalDate.of(a, 7, 1), false, Set.of(),
                    List.of("ingenier", "constru", "industrial"),
                    "Reconoce a los ingenieros del equipo y lo que construyen."),
            new Fecha("ABOGADO", "Día del Abogado", a -> LocalDate.of(a, 7, 12), false, Set.of(),
                    List.of("abogad", "juridic", "legal", "notari"),
                    "Celebra el Día del Abogado con un dato legal util para los clientes."),
            new Fecha("CLASES", "Regreso a clases", a -> LocalDate.of(a, 8, 20), false, Set.of(),
                    List.of("papeler", "escuela", "uniform", "optica", "mochila", "libreria"),
                    "Acompaña el regreso a clases con lo que el negocio ofrece a familias y estudiantes."),
            new Fecha("PATRIAS", "Fiestas Patrias", a -> LocalDate.of(a, 9, 15), true, Set.of(), List.of(),
                    "Celebra las Fiestas Patrias con orgullo mexicano y el estilo del negocio."),
            new Fecha("ARQUITECTO", "Día del Arquitecto", a -> LocalDate.of(a, 10, 1), false, Set.of(),
                    List.of("arquitect", "constru", "diseno de interiores", "remodela"),
                    "Reconoce a los arquitectos y el diseño detrás de cada obra."),
            new Fecha("MEDICO", "Día del Médico", a -> LocalDate.of(a, 10, 23), false, Set.of(),
                    List.of("medic", "clinica", "hospital", "consultorio", "salud"),
                    "Agradece a los médicos su vocacion, con un mensaje cercano."),
            new Fecha("MUERTOS", "Día de Muertos", a -> LocalDate.of(a, 11, 1), true, Set.of(), List.of(),
                    "Honra la tradicion del Día de Muertos con el estilo del negocio, con respeto."),
            new Fecha("BUEN_FIN", "El Buen Fin",
                    // Termina el lunes del día de la Revolución (tercer lunes de noviembre); empieza el jueves antes.
                    a -> LocalDate.of(a, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
                            .minusDays(4),
                    false, VENDE, List.of(),
                    "Anuncia que el negocio participa en El Buen Fin; invita a aprovechar sin inventar descuentos."),
            new Fecha("NAVIDAD", "Navidad", a -> LocalDate.of(a, 12, 24), true, Set.of(), List.of(),
                    "Desea una feliz Navidad a clientes y comunidad, con calidez y el estilo del negocio."),
            new Fecha("FIN_DE_ANIO", "Fin de año", a -> LocalDate.of(a, 12, 31), true, Set.of(), List.of(),
                    "Agradece el año a los clientes y desea un buen año nuevo; si el negocio trabaja por proyecto, "
                            + "recuerda lo logrado."));

    private FechasDelAnio() {
    }

    /**
     * Las fechas que le tocan al negocio en los próximos {@code dias} (desde
     * hoy, incluido), de la más cercana a la más lejana.
     */
    public static List<Proxima> proximas(LocalDate hoy, int dias, String giro, String descripcion,
            Set<RasgoDelNegocio> rasgos) {
        String texto = normal((giro == null ? "" : giro) + " " + (descripcion == null ? "" : descripcion));
        LocalDate hasta = hoy.plusDays(dias);
        List<Proxima> salida = new ArrayList<>();
        for (Fecha f : TODAS) {
            if (!f.leToca(texto, rasgos)) {
                continue;
            }
            for (int anio : new int[] {hoy.getYear(), hoy.getYear() + 1}) {
                LocalDate dia = f.dia().apply(anio);
                if (!dia.isBefore(hoy) && !dia.isAfter(hasta)) {
                    salida.add(new Proxima(f, dia));
                }
            }
        }
        salida.sort(Comparator.comparing(Proxima::dia));
        return salida;
    }

    static String normal(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}

package com.metricol.api.service.agente;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.ai.OpenAiClient;

/**
 * Organiza una tanda de fotos como un community manager: primero se analizó
 * cada una (RevisorDeMarca); aquí se decide qué publicaciones salen de todas.
 *
 * <ul>
 * <li><b>Carrusel</b>: 2 o más fotos del mismo tema (el mismo producto, evento
 * o lugar) cuentan una historia juntas. La portada es la más fuerte.</li>
 * <li><b>Historia</b>: una foto vertical de algo del momento (la promoción de
 * hoy, detrás de cámaras): no compite con el feed.</li>
 * <li><b>Post</b>: una foto que se sostiene sola.</li>
 * </ul>
 *
 * <p>Dos piezas: {@link #proponer} pregunta a la IA (una llamada de texto por
 * tanda, sin volver a mirar las fotos), y {@link #normalizar} aplica las
 * reglas fijas a lo que conteste, o decide solo si no contestó. La IA sugiere;
 * las reglas mandan.
 */
@Component
public class OrganizadorDeContenido {

    private static final Logger log = LoggerFactory.getLogger(OrganizadorDeContenido.class);

    public enum Formato { CARRUSEL, POST, HISTORIA }

    /** Lo que se sabe de cada foto de la tanda, numerada desde 1. */
    public record Foto(int n, RevisorDeMarca.Revision revision) {
    }

    /** Una publicación: su formato y sus fotos (números), en orden; la primera es la portada. */
    public record Grupo(Formato formato, List<Integer> fotos, String tema, String porque) {
    }

    private static final String SISTEMA = """
            Eres el community manager de un negocio. Te paso las fotos que subio
            hoy, ya revisadas (que se ve, de que tema, si es del momento, si es
            vertical, que tan fuerte es). Decides que publicaciones salen, como lo
            haria una agencia:

            - CARRUSEL: 2 o mas fotos del MISMO tema (el mismo producto, el mismo
              lugar, el mismo evento, un antes y despues). Nunca mezcles temas.
              Ordenalas: primero la mas fuerte (la portada que detiene el scroll),
              luego un recorrido logico (vista general, detalles, uso, cierre).
            - HISTORIA: una sola foto VERTICAL de algo del momento (la promocion de
              hoy, un evento en curso, detras de camaras, el dia a dia).
            - POST: una foto que se sostiene sola y no tiene pareja de su tema.

            Cada foto va en una sola publicacion. Si te dan una instruccion de la
            persona, la sigues.

            Contestas SOLO un JSON:
            {"publicaciones": [
              {"formato": "CARRUSEL" | "POST" | "HISTORIA",
               "fotos": [numeros de foto, en orden],
               "tema": "de que trata, pocas palabras",
               "porque": "una frase para el dueno"}
            ]}
            """;

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrganizadorDeContenido(OpenAiClient client) {
        this.client = client;
    }

    /**
     * Lo que propone la IA para la tanda, crudo. Nunca lanza: si falla devuelve
     * nulo y {@link #normalizar} decide con las reglas.
     *
     * @param instruccion lo que pidió la persona ("sepáralas", "quita la 3"), o nulo
     */
    public List<Grupo> proponer(List<Foto> fotos, String instruccion) {
        if (fotos.size() < 2) {
            return null;
        }
        try {
            return interpretar(client.completeJson(AiOperacion.AGENTE_ORGANIZAR, SISTEMA, prompt(fotos, instruccion)));
        } catch (Exception ex) {
            log.warn("El agente no pudo organizar la tanda: {}", ex.toString());
            return null;
        }
    }

    static String prompt(List<Foto> fotos, String instruccion) {
        StringBuilder sb = new StringBuilder("Las fotos:\n");
        for (Foto f : fotos) {
            RevisorDeMarca.Revision r = f.revision();
            sb.append(f.n()).append(". tema: ").append(r.tema().isBlank() ? "(sin tema)" : r.tema())
                    .append(" | tipo: ").append(r.tipo())
                    .append(" | ").append(r.orientacion().toLowerCase(Locale.ROOT))
                    .append(r.efimero() ? " | del momento" : "")
                    .append(" | fuerza ").append(r.diagnostico().fuerza()).append("/5")
                    .append(" | se ve: ").append(r.descripcion())
                    .append('\n');
        }
        if (instruccion != null && !instruccion.isBlank()) {
            sb.append("\nInstruccion de la persona: ").append(instruccion.strip()).append('\n');
        }
        return sb.toString();
    }

    List<Grupo> interpretar(String respuesta) throws Exception {
        if (respuesta == null) {
            return null;
        }
        int inicio = respuesta.indexOf('{');
        int fin = respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            return null;
        }
        JsonNode raiz = mapper.readTree(respuesta.substring(inicio, fin + 1));
        List<Grupo> grupos = new ArrayList<>();
        for (JsonNode p : raiz.path("publicaciones")) {
            Formato formato;
            try {
                formato = Formato.valueOf(p.path("formato").asText("POST").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                formato = Formato.POST;
            }
            List<Integer> nums = new ArrayList<>();
            for (JsonNode n : p.path("fotos")) {
                if (n.canConvertToInt()) {
                    nums.add(n.asInt());
                }
            }
            grupos.add(new Grupo(formato, nums, p.path("tema").asText("").strip(), p.path("porque").asText("").strip()));
        }
        return grupos;
    }

    // ------------------------------------------------------------ las reglas

    /**
     * Lo propuesto, corregido con las reglas fijas; sin propuesta, solo las reglas.
     * <ul>
     * <li>Cada foto en una sola publicación; números que no existen, fuera.</li>
     * <li>Carrusel de 2 a {@code maxCarrusel}: con una foto es post; con más,
     * se parte (y lo que quede suelto es post).</li>
     * <li>Historia: una foto, vertical, y solo si hay redes de historias; si
     * no, post.</li>
     * <li>La foto que nadie acomodó sale con la regla de una sola.</li>
     * </ul>
     */
    public static List<Grupo> normalizar(List<Grupo> propuesta, List<Foto> fotos, int maxCarrusel,
            boolean hayHistorias) {
        Set<Integer> existentes = new LinkedHashSet<>();
        fotos.forEach(f -> existentes.add(f.n()));
        Set<Integer> usadas = new LinkedHashSet<>();
        List<Grupo> salida = new ArrayList<>();
        int tope = Math.max(2, maxCarrusel);

        if (propuesta != null) {
            for (Grupo g : propuesta) {
                List<Integer> suyas = new ArrayList<>();
                for (Integer n : g.fotos()) {
                    if (n != null && existentes.contains(n) && !usadas.contains(n)) {
                        suyas.add(n);
                        usadas.add(n);
                    }
                }
                if (suyas.isEmpty()) {
                    continue;
                }
                switch (g.formato()) {
                    case CARRUSEL -> {
                        for (int i = 0; i < suyas.size(); i += tope) {
                            List<Integer> trozo = suyas.subList(i, Math.min(i + tope, suyas.size()));
                            if (trozo.size() >= 2) {
                                salida.add(new Grupo(Formato.CARRUSEL, List.copyOf(trozo), g.tema(), g.porque()));
                            } else {
                                salida.add(sola(fotoDe(fotos, trozo.get(0)), hayHistorias, false));
                            }
                        }
                    }
                    case HISTORIA -> suyas.forEach(n -> {
                        Foto f = fotoDe(fotos, n);
                        salida.add(hayHistorias && f.revision().vertical()
                                ? new Grupo(Formato.HISTORIA, List.of(n), g.tema(), g.porque())
                                : new Grupo(Formato.POST, List.of(n), g.tema(), g.porque()));
                    });
                    default -> suyas.forEach(n -> salida.add(new Grupo(Formato.POST, List.of(n), g.tema(), g.porque())));
                }
            }
        }
        for (Foto f : fotos) {
            if (!usadas.contains(f.n())) {
                salida.add(sola(f, hayHistorias, true));
            }
        }
        return salida;
    }

    /**
     * Una foto sola: historia si es vertical y del momento (y hay a dónde
     * mandarla); si no, post.
     */
    static Grupo sola(Foto f, boolean hayHistorias, boolean porRegla) {
        RevisorDeMarca.Revision r = f.revision();
        boolean historia = hayHistorias && r.vertical() && r.efimero();
        return new Grupo(historia ? Formato.HISTORIA : Formato.POST, List.of(f.n()), r.tema(),
                porRegla && historia ? "Es del momento y está en vertical: va de historia." : "");
    }

    private static Foto fotoDe(List<Foto> fotos, int n) {
        return fotos.stream().filter(f -> f.n() == n).findFirst().orElseThrow();
    }
}

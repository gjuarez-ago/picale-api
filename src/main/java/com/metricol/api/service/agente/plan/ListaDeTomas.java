package com.metricol.api.service.agente.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.RasgoDelNegocio;
import com.metricol.api.service.ai.OpenAiClient;

/**
 * Las fotos que el asistente le pide al dueño cada semana, como lo haría un
 * community manager: pocas (tres), concretas para SU negocio, posibles con un
 * teléfono y con un consejo de cómo tomarlas. Si viene una fecha que le toca,
 * una de ellas es para esa fecha.
 *
 * <p>Las escribe la IA con el giro y los rasgos; si no se puede, salen de un
 * catálogo por rasgo. Nunca se queda sin lista.
 */
@Component
public class ListaDeTomas {

    private static final Logger log = LoggerFactory.getLogger(ListaDeTomas.class);

    /** Una foto que se pide: qué y cómo. */
    public record Toma(String que, String consejo) {
    }

    static final int CUANTAS = 3;

    static final String SISTEMA = """
            Eres el community manager de un negocio local en Mexico. Cada semana
            le pides al dueno las fotos que necesitas para sus redes. Le hablas
            de tu a tu, claro y corto.

            Contestas SOLO un JSON:
            {"tomas": [{"que": "la foto, concreta para ESTE negocio, en 4 a 10 palabras",
                        "consejo": "como tomarla con el telefono, en una frase (luz, angulo, que evitar)"}]}

            Reglas:
            - Exactamente 3 tomas, distintas entre si y posibles hoy con un telefono.
            - Concretas para su giro: a una constructora, "la obra terminada de
              frente"; a una taqueria, "el trompo recien armado".
            - Si te dan una fecha cercana, una de las tomas es para esa fecha.
            - Si trabaja por proyecto, incluye un antes y despues (misma posicion
              y angulo en las dos).
            - Nada de caras de clientes sin su permiso; nada de precios.
            """;

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ListaDeTomas(OpenAiClient client) {
        this.client = client;
    }

    /** Las tomas de esta semana; nunca vacía. */
    public List<Toma> semana(Workspace w, List<FechasDelAnio.Proxima> fechas) {
        Set<RasgoDelNegocio> rasgos = w.rasgos();
        try {
            String respuesta = client.completeJson(AiOperacion.AGENTE_PLANEAR, SISTEMA, pedido(w, rasgos, fechas));
            List<Toma> tomas = interpretar(respuesta);
            if (tomas.size() == CUANTAS) {
                return tomas;
            }
        } catch (Exception ex) {
            log.info("Tomas de {} por catálogo: {}", w.getId(), ex.toString());
        }
        return catalogo(rasgos, fechas);
    }

    static String pedido(Workspace w, Set<RasgoDelNegocio> rasgos, List<FechasDelAnio.Proxima> fechas) {
        StringBuilder t = new StringBuilder("El negocio:\n");
        linea(t, "Nombre", w.getName());
        linea(t, "Giro", w.getGiro());
        linea(t, "A que se dedica", w.getDescripcion());
        if (rasgos != null && !rasgos.isEmpty()) {
            linea(t, "Como trabaja", String.join("; ", rasgos.stream().map(r -> r.etiqueta).toList()));
        }
        if (!fechas.isEmpty()) {
            FechasDelAnio.Proxima p = fechas.get(0);
            linea(t, "Fecha cercana", p.fecha().nombre() + " (" + p.dia() + "): " + p.fecha().idea());
        }
        t.append("\nDame las 3 fotos de esta semana.");
        return t.toString();
    }

    List<Toma> interpretar(String respuesta) throws Exception {
        List<Toma> tomas = new ArrayList<>();
        if (respuesta == null) {
            return tomas;
        }
        int inicio = respuesta.indexOf('{');
        int fin = respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            return tomas;
        }
        JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1)).path("tomas");
        if (n.isArray()) {
            for (JsonNode t : n) {
                String que = recortar(t.path("que").asText(""), 90);
                String consejo = recortar(t.path("consejo").asText(""), 160);
                if (!que.isBlank() && tomas.size() < CUANTAS) {
                    tomas.add(new Toma(que, consejo));
                }
            }
        }
        return tomas;
    }

    /** Sin IA: por rasgo, con la fecha cercana primero. Siempre tres. */
    static List<Toma> catalogo(Set<RasgoDelNegocio> rasgos, List<FechasDelAnio.Proxima> fechas) {
        Set<Toma> salida = new LinkedHashSet<>();
        if (!fechas.isEmpty()) {
            salida.add(new Toma("Algo de tu negocio para " + fechas.get(0).fecha().nombre(),
                    "Con luz natural y de frente: la preparo para ese día."));
        }
        Set<RasgoDelNegocio> r = rasgos == null ? Set.of() : rasgos;
        if (r.contains(RasgoDelNegocio.POR_PROYECTO)) {
            salida.add(new Toma("Un trabajo terminado, completo y de frente",
                    "Por la mañana o al atardecer, sin herramientas ni basura a la vista."));
            salida.add(new Toma("Un antes y después del mismo lugar",
                    "Párate en el mismo punto y con el mismo ángulo en las dos fotos."));
            salida.add(new Toma("Tu equipo trabajando en obra",
                    "Que se vea el trabajo, no las caras de cerca; con casco y equipo puesto."));
        }
        if (r.contains(RasgoDelNegocio.PRODUCTO)) {
            salida.add(new Toma("Tu producto estrella sobre un fondo limpio",
                    "Junto a una ventana, sin flash, llenando casi todo el cuadro."));
            salida.add(new Toma("Tu producto en uso o en manos de un cliente",
                    "Que se vea cómo se usa; pide permiso si sale alguien."));
        }
        if (r.contains(RasgoDelNegocio.LOCAL)) {
            salida.add(new Toma("Tu local por fuera, de día", "Con la fachada derecha y sin coches tapando."));
        }
        if (r.contains(RasgoDelNegocio.EDUCA)) {
            salida.add(new Toma("Una herramienta o material de tu oficio que puedas explicar",
                    "De cerca y con buena luz: la uso para un consejo."));
        }
        salida.add(new Toma("Tu equipo en un día normal", "Natural, sin posar; que se vea el ambiente."));
        salida.add(new Toma("Un detalle de lo que haces que te enorgullece", "De cerca, con luz de ventana."));
        return new ArrayList<>(salida).subList(0, CUANTAS);
    }

    private static void linea(StringBuilder t, String etiqueta, String valor) {
        if (valor != null && !valor.isBlank()) {
            t.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }

    private static String recortar(String s, int max) {
        String limpio = s == null ? "" : s.strip();
        return limpio.length() <= max ? limpio : limpio.substring(0, max);
    }
}

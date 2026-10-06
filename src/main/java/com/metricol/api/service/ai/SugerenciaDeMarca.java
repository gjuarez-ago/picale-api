package com.metricol.api.service.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.PilarDeContenido;

/**
 * "Sugerir con IA" en Marca: a partir de lo que vende y a quién le habla,
 * propone la voz de la marca —su historia, sus valores, frases motivadoras
 * para su público y de qué hablar—. Es una propuesta: la pantalla la pone en
 * el formulario y el dueño la edita antes de guardar. Nunca se guarda sola.
 */
@Component
public class SugerenciaDeMarca {

    /** Lo que propone. Las frases, una por elemento. */
    public record Sugerencia(String historia, String valores, List<String> frases, List<String> pilares) {
    }

    static final String SISTEMA;

    static {
        StringBuilder t = new StringBuilder("""
                Eres estratega de marca para negocios locales en Mexico. Con lo que
                te cuentan de un negocio, propones como suena en redes: su historia,
                sus valores, frases que motiven a su publico y de que hablar.

                Contestas SOLO un JSON:
                {"historia": "2 o 3 frases en primera persona del plural: por que existe el negocio y para quien trabaja",
                 "valores": "3 o 4 valores separados por coma, concretos",
                 "frases": ["5 frases cortas (max 90 caracteres) que motiven a SU publico y conecten con su oficio"],
                 "pilares": ["CODIGO", ...]}

                Pilares posibles (elige 3 a 5 que le sirvan):
                """);
        for (PilarDeContenido p : PilarDeContenido.values()) {
            t.append("- ").append(p.name()).append(": ").append(p.descripcion).append('\n');
        }
        t.append("""

                Reglas:
                - No inventes datos (anos, premios, numeros, nombres). Si no sabes la
                  historia, escribela general y honesta, sin fechas.
                - Las frases son originales, no citas de famosos. Nada de cliches
                  vacios ("el exito es de los que nunca se rinden"): que se note el
                  oficio. Para una constructora: "Lo que se construye con paciencia
                  dura generaciones."
                - Espanol de Mexico, cercano, sin emojis ni hashtags.
                """);
        SISTEMA = t.toString();
    }

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public SugerenciaDeMarca(OpenAiClient client) {
        this.client = client;
    }

    public Sugerencia sugerir(Workspace w) {
        String respuesta = client.completeJson(AiOperacion.SUGERIR_MARCA, SISTEMA, pedido(w));
        try {
            return interpretar(respuesta);
        } catch (Exception ex) {
            throw new IllegalStateException("No pude sugerir ahora. Intenta de nuevo en un momento.");
        }
    }

    static String pedido(Workspace w) {
        MarcaDelNegocio m = MarcaDelNegocio.delEspacio(w);
        StringBuilder t = new StringBuilder("El negocio:\n");
        linea(t, "Nombre", w.getName());
        linea(t, "Giro", w.getGiro());
        linea(t, "Ciudad", w.getCiudad());
        linea(t, "A que se dedica", w.getDescripcion());
        linea(t, "Que vende o destaca", m.queVende());
        linea(t, "A quien le habla", m.publico());
        linea(t, "Como suena", m.personalidadEs());
        linea(t, "Como trabaja", m.comoTrabajaEs());
        linea(t, "Su historia (si ya la escribio, mejorala sin inventar)", m.historia());
        return t.toString();
    }

    Sugerencia interpretar(String respuesta) throws Exception {
        int inicio = respuesta == null ? -1 : respuesta.indexOf('{');
        int fin = respuesta == null ? -1 : respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            throw new IllegalStateException("Respuesta sin JSON");
        }
        JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1));
        List<String> frases = new ArrayList<>();
        n.path("frases").forEach(f -> {
            String x = f.asText("").strip();
            if (!x.isBlank() && frases.size() < 6) {
                frases.add(x.length() <= 120 ? x : x.substring(0, 120));
            }
        });
        List<String> pilares = new ArrayList<>();
        n.path("pilares").forEach(p -> pilares.add(p.asText("")));
        return new Sugerencia(recortar(n.path("historia").asText(""), 600), recortar(n.path("valores").asText(""), 300),
                frases, PilarDeContenido.de(pilares).stream().map(Enum::name).toList());
    }

    private static void linea(StringBuilder t, String etiqueta, String valor) {
        if (valor != null && !valor.isBlank()) {
            t.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }

    private static String recortar(String s, int max) {
        String l = s == null ? "" : s.strip();
        return l.length() <= max ? l : l.substring(0, max);
    }
}

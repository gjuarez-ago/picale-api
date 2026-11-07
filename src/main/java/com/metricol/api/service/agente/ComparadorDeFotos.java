package com.metricol.api.service.agente;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.ai.OpenAiClient;

/**
 * Confirma con la vista si dos fotos que la huella da por parecidas son de
 * verdad la misma.
 *
 * <p>La huella ({@link com.metricol.api.service.media.HuellaDeImagen}) mira la
 * foto en 9×8 píxeles: ahí no se lee ningún texto. Los flyers de una misma
 * plantilla (el mismo fondo con otro paquete y otro precio) le salen iguales,
 * y el agente los descartaba como repetidos. La huella sirve de filtro barato;
 * quien decide es esto, y solo se le pregunta cuando la huella ya dijo que sí.
 */
@Service
public class ComparadorDeFotos {

    private static final Logger log = LoggerFactory.getLogger(ComparadorDeFotos.class);

    private static final String SISTEMA = """
            Te llegan dos imagenes de un mismo negocio. Dices si son la MISMA
            pieza, que sobra publicar dos veces, o dos piezas DISTINTAS.

            MISMA: la misma toma o el mismo diseno, aunque cambie el recorte,
            el tamano, la compresion, el brillo, un poco el angulo o el
            momento (dos fotos seguidas de lo mismo).

            DISTINTA: cambia lo que comunica. Un flyer o diseno con otro
            texto, otro producto, otro precio, otra fecha u otro paquete es
            DISTINTA aunque use la misma plantilla y el mismo fondo. Tambien
            si sale otro platillo, otra persona u otro lugar.

            Lee el texto de las dos antes de decidir. Ante la duda, DISTINTA.

            Contestas SOLO un JSON: {"misma": true | false, "diferencia": "en pocas palabras, que cambia"}
            """;

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ComparadorDeFotos(OpenAiClient client) {
        this.client = client;
    }

    /**
     * ¿Son la misma foto? Nunca lanza. Si la IA falla contesta que no: una
     * repetida que pasa le cuesta a la persona un toque para descartarla; una
     * distinta descartada es trabajo perdido que nadie ve.
     */
    public boolean misma(String urlA, String urlB) {
        try {
            String respuesta = client.describeImages(AiOperacion.AGENTE_COMPARAR, SISTEMA,
                    "Imagen 1 e imagen 2. ¿Son la misma pieza?", List.of(urlA, urlB));
            return interpretar(respuesta);
        } catch (Exception ex) {
            log.warn("No se pudo comparar {} con {}: {}", urlA, urlB, ex.toString());
            return false;
        }
    }

    boolean interpretar(String respuesta) throws Exception {
        if (respuesta == null) {
            return false;
        }
        int inicio = respuesta.indexOf('{');
        int fin = respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            return false;
        }
        JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1));
        JsonNode misma = n.path("misma");
        if (misma.isBoolean()) {
            return misma.booleanValue();
        }
        return "true".equals(misma.asText("").strip().toLowerCase(Locale.ROOT));
    }
}

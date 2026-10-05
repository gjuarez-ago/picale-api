package com.metricol.api.service.agente.foto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.ai.Redactor;

/**
 * El director de foto del agente: mira UNA foto real en alta resolución,
 * como lo haría un retocador profesional, y decide tres cosas.
 *
 * <ol>
 * <li><b>¿Vale la pena mejorarla?</b> Solo si un retoque fiel la sube de
 * "foto de teléfono" a "foto profesional". Una que ya se ve bien no se toca:
 * cuesta y arriesga sin ganar nada.</li>
 * <li><b>Cómo</b>: escribe el encargo para el modelo de imágenes a la medida
 * de lo que le falta a ESA foto (líneas chuecas, sombras tapadas, cielo
 * quemado, dominante amarilla…) y lista lo que no puede cambiar.</li>
 * <li><b>Dónde va el logo y de qué tamaño</b>: en la zona de menos peso
 * visual (suelo, cielo, pared lisa), nunca sobre el trabajo, una cara o un
 * letrero.</li>
 * </ol>
 *
 * <p>Corre en segundo plano con un modelo que razona: aquí se paga detalle,
 * no velocidad. Nunca lanza: sin dirección, el agente sigue como antes.
 */
@Component
public class DirectorDeFoto {

    private static final Logger log = LoggerFactory.getLogger(DirectorDeFoto.class);

    /** Las seis posiciones que sabe sellar {@code SelloDeLogo}. */
    static final Set<String> ZONAS = Set.of("TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER",
            "BOTTOM_RIGHT");

    public enum TamanoLogo {
        CHICO(0.22), MEDIANO(0.30), GRANDE(0.38);

        /** Ancho del logo respecto al ancho de la foto. */
        public final double ancho;

        TamanoLogo(double ancho) {
            this.ancho = ancho;
        }
    }

    /**
     * Lo que decidió.
     *
     * @param mejorar      si vale la pena mejorarla con IA
     * @param deficiencias lo que le falta, en palabras para el dueño ("líneas chuecas", "sombras tapadas")
     * @param encargo      el prompt para el modelo de imágenes, ya a la medida de la foto
     * @param conservar    lo que no puede cambiar (materiales, letreros, personas…)
     * @param logoZona     una de {@link #ZONAS}
     * @param logoTamano   qué tanto pesa el logo en la foto
     * @param porque       por qué el logo va ahí, para el registro
     */
    public record Direccion(boolean mejorar, List<String> deficiencias, String encargo, List<String> conservar,
            String logoZona, TamanoLogo logoTamano, String porque) {

        public String deficienciasEnFrase() {
            if (deficiencias.isEmpty()) {
                return "detalles de luz y color";
            }
            if (deficiencias.size() == 1) {
                return deficiencias.get(0);
            }
            return String.join(", ", deficiencias.subList(0, deficiencias.size() - 1)) + " y "
                    + deficiencias.get(deficiencias.size() - 1);
        }
    }

    static final String SISTEMA = """
            You are a senior commercial photo retoucher and art director for small
            businesses' social media. You receive ONE real photo the business took
            with a phone. Your job is to decide how to make it look professionally
            shot WITHOUT losing realism, and where the business logo should go.

            Answer ONLY a JSON object:
            {"mejorar": true | false,
             "deficiencias": ["2 to 4 short items IN SPANISH for the owner, e.g. lineas chuecas, sombras muy oscuras, cielo quemado, color amarillento, poca nitidez"],
             "encargo": "the editing instructions IN ENGLISH for an image-editing model (see below)",
             "conservar": ["IN ENGLISH: the concrete things that must stay identical in THIS photo"],
             "logo": {"zona": "TOP_LEFT" | "TOP_CENTER" | "TOP_RIGHT" | "BOTTOM_LEFT" | "BOTTOM_CENTER" | "BOTTOM_RIGHT",
                      "tamano": "CHICO" | "MEDIANO" | "GRANDE",
                      "porque": "one short sentence in Spanish"}}

            1. mejorar
            Evaluate like a professional: perspective (converging or leaning
            verticals, tilted horizon), exposure and dynamic range (crushed
            shadows, blown highlights or sky), white balance and color casts,
            flat or muddy contrast, texture clarity and sharpness, noise, haze,
            and framing (distracting edges that a slight straighten/crop fixes).
            true ONLY when a faithful retouch would clearly lift the photo to a
            professional look. false when:
            - it already looks professional, or the gain would be marginal;
            - it is motion-blurred, out of focus or heavily pixelated (a retouch
              cannot honestly recover it);
            - it is a flyer, screenshot, graphic, or has text/design on top;
            - people's faces are the main subject (portraits must not be
              altered).

            2. encargo (only when mejorar is true; otherwise empty)
            Write precise, imperative instructions tailored to the deficiencies
            you found in THIS photo. Name the regions ("the stone floor", "the
            back wall", "the sky behind the trees") and the exact correction
            ("lift the shadows on the seating wall about one stop", "neutralize
            the yellow cast to daylight white", "straighten the vertical lines of
            the column", "recover detail in the bright sky"). Typical goals:
            straight verticals and level horizon, balanced natural exposure with
            detail in shadows and highlights, accurate neutral white balance,
            crisp natural texture of materials (stone, concrete, wood, metal,
            fabric, food), clean gentle contrast, noise reduction, a subtle
            clarity boost. The look is a professional architectural or product
            photo: natural daylight, true-to-life color, no HDR halos, no
            oversaturation, no painterly or plastic look, no added bokeh.
            Never ask to add, remove, move or replace anything; never ask for
            text, logos, watermarks or frames.

            3. conservar
            List what makes this photo true: the subject's shape and
            proportions, materials and their real colors, every object in its
            place, any signage or text, people, the background landmarks.

            4. logo
            The real logo file will be stamped on the final photo. Choose the
            zone with the LEAST visual importance and the most uniform texture
            (ground, gravel, floor, sky, plain wall) where it will not cover the
            main subject, the work being shown, a face, or any text. Prefer the
            bottom corners when the foreground is ground or floor; prefer the top
            when the top is sky or a plain wall. Size: CHICO for busy or elegant
            photos, MEDIANO by default, GRANDE only when a large calm area
            exists and the photo is a portfolio shot of the business's work.
            """;

    private final OpenAiClient client;
    private final OpenAiProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public DirectorDeFoto(OpenAiClient client, OpenAiProperties props) {
        this.client = client;
        this.props = props;
    }

    public boolean disponible() {
        return props.isConfigured();
    }

    /**
     * @param url         la foto, pública
     * @param negocio     para que sepa qué es "el trabajo" en la foto
     * @param descripcion lo que ya vio el revisor de marca
     * @return la dirección, o {@code null} si no se pudo
     */
    public Direccion dirigir(String url, Redactor.Negocio negocio, String descripcion) {
        if (!disponible() || url == null) {
            return null;
        }
        try {
            String modelo = hay(props.getPhotoDirectorModel()) ? props.getPhotoDirectorModel()
                    : props.getDirectorModel();
            String respuesta = client.mirar(AiOperacion.AGENTE_DIRIGIR_FOTO, modelo,
                    props.getPhotoDirectorReasoningEffort(), SISTEMA, pedido(negocio, descripcion), List.of(url),
                    "high", props.getDirectorPricing());
            return interpretar(respuesta);
        } catch (Exception ex) {
            log.warn("El director de foto no pudo mirar {}: {}", url, ex.toString());
            return null;
        }
    }

    static String pedido(Redactor.Negocio n, String descripcion) {
        StringBuilder t = new StringBuilder();
        if (n != null) {
            t.append("Business: ").append(hay(n.nombre()) ? n.nombre().strip() : "a local business");
            if (hay(n.giro())) {
                t.append(" (").append(n.giro().strip()).append(")");
            }
            t.append('\n');
            if (hay(n.descripcion())) {
                t.append("About it: ").append(n.descripcion().strip()).append('\n');
            }
            if (n.marca() != null && n.marca().tiene(com.metricol.api.enums.RasgoDelNegocio.POR_PROYECTO)) {
                t.append("It works by project: the photo shows its work, the logo signs it like a portfolio.\n");
            }
        }
        if (hay(descripcion)) {
            t.append("What a first reviewer saw (Spanish): ").append(descripcion.strip()).append('\n');
        }
        t.append("Direct this photo.");
        return t.toString();
    }

    Direccion interpretar(String respuesta) throws Exception {
        if (respuesta == null) {
            return null;
        }
        int inicio = respuesta.indexOf('{');
        int fin = respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            return null;
        }
        JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1));
        String encargo = recortar(n.path("encargo").asText(""), 2500);
        // Sin encargo no hay qué mandar al modelo de imágenes: no se mejora.
        boolean mejorar = n.path("mejorar").asBoolean(false) && encargo.length() >= 40;

        JsonNode logo = n.path("logo");
        String zona = logo.path("zona").asText("BOTTOM_RIGHT").strip().toUpperCase(Locale.ROOT);
        if (!ZONAS.contains(zona)) {
            zona = "BOTTOM_RIGHT";
        }
        TamanoLogo tamano;
        try {
            tamano = TamanoLogo.valueOf(logo.path("tamano").asText("MEDIANO").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            tamano = TamanoLogo.MEDIANO;
        }
        return new Direccion(mejorar, lista(n.path("deficiencias"), 4, 60), mejorar ? encargo : "",
                lista(n.path("conservar"), 10, 160), zona, tamano, recortar(logo.path("porque").asText(""), 200));
    }

    private static List<String> lista(JsonNode nodo, int maximo, int largo) {
        List<String> salida = new ArrayList<>();
        if (nodo != null && nodo.isArray()) {
            for (JsonNode e : nodo) {
                String texto = recortar(e.asText(""), largo);
                if (!texto.isBlank() && salida.size() < maximo) {
                    salida.add(texto);
                }
            }
        }
        return List.copyOf(salida);
    }

    private static boolean hay(String s) {
        return s != null && !s.isBlank();
    }

    private static String recortar(String s, int max) {
        String limpio = s == null ? "" : s.strip();
        return limpio.length() <= max ? limpio : limpio.substring(0, max);
    }
}

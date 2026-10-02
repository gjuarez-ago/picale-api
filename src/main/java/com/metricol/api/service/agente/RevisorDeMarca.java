package com.metricol.api.service.agente;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.ai.Redactor;

/**
 * Mira una foto con los ojos de la marca: ¿va, no se sabe, o no va?
 *
 * <p>Es el primer paso del agente y el más barato. Va antes de escribir nada y
 * antes de gastar un crédito: lo que no va con la marca no merece ni el texto.
 * El criterio es la sección de Marca (giro, qué vende, a quién le habla, qué
 * evitar) más unas reglas de cuidado que valen para cualquier cuenta.
 *
 * <p>Ante la duda, Observación y no Descartada: una foto buena descartada es
 * trabajo perdido que nadie ve; una dudosa en Observación cuesta un toque.
 */
@Service
public class RevisorDeMarca {

    private static final Logger log = LoggerFactory.getLogger(RevisorDeMarca.class);

    public enum Veredicto { VA, OBSERVACION, DESCARTADA }

    /**
     * Lo que dijo la IA.
     *
     * @param motivo      por qué, en una frase para la persona
     * @param descripcion qué se ve, para quien escribe el texto
     * @param idea        qué comunicaría un community manager con esta foto
     * @param diagnostico cómo está la foto; con esto decide {@link DecisorDelAgente}
     * @param orientacion VERTICAL, HORIZONTAL o CUADRADA: una historia solo con vertical
     * @param efimero     es del momento (la promoción de hoy, un evento en curso, detrás de
     *                    cámaras): va mejor en historias que en el feed
     * @param tema        de qué trata, en pocas palabras ("depa Calle 60", "tacos al pastor"):
     *                    con esto se agrupan las de una tanda en carruseles
     */
    public record Revision(Veredicto veredicto, String motivo, String descripcion, String idea,
            DecisorDelAgente.Diagnostico diagnostico, String orientacion, boolean efimero, String tema) {

        public Revision {
            orientacion = orientacion == null || orientacion.isBlank() ? "CUADRADA" : orientacion;
            tema = tema == null ? "" : tema;
        }

        /** Sin lo de organizar (orientación, momento, tema): lo de antes y las pruebas. */
        public Revision(Veredicto veredicto, String motivo, String descripcion, String idea,
                DecisorDelAgente.Diagnostico diagnostico) {
            this(veredicto, motivo, descripcion, idea, diagnostico, "CUADRADA", false, "");
        }

        /** Una foto buena del tipo dado: las pruebas y lo que no trae diagnóstico. */
        public Revision(Veredicto veredicto, String motivo, String descripcion, String idea, String tipo) {
            this(veredicto, motivo, descripcion, idea, new DecisorDelAgente.Diagnostico(4, "", true, 4, false,
                    false, DecisorDelAgente.Intencion.VENDER, tipo));
        }

        public String tipo() {
            return diagnostico.tipo();
        }

        public boolean vertical() {
            return "VERTICAL".equals(orientacion);
        }
    }

    private static final String SISTEMA = """
            Eres el community manager de un negocio. Te llega una foto que el
            negocio subio. Decides si va con la marca y la calificas; lo que se
            hace con ella (retocarla, disenarla, ponerle logo) lo decide otro
            paso con tu calificacion, asi que se honesto con los numeros.

            Contestas SOLO un JSON:
            {"veredicto": "VA" | "OBSERVACION" | "DESCARTADA",
             "motivo": "una frase para el dueno, en espanol, diciendo por que",
             "descripcion": "que se ve, concreto, una o dos frases",
             "idea": "que comunicarias con esta foto, en una frase, como encargo para quien escribe",
             "tipo": "PRODUCTO" | "LUGAR" | "EQUIPO" | "EVENTO" | "PROMOCION" | "TESTIMONIO" | "OTRO",
             "calidad": 1-5,
             "queFalla": "si la calidad es baja, que le falta en dos o tres palabras: oscura, borrosa, torcida, mal recortada",
             "arreglable": true | false,
             "fuerza": 1-5,
             "esArte": true | false,
             "necesitaTexto": true | false,
             "intencion": "VENDER" | "INFORMAR" | "COMUNIDAD" | "CONFIANZA",
             "orientacion": "VERTICAL" | "HORIZONTAL" | "CUADRADA",
             "efimero": true | false,
             "tema": "de que trata en 2 a 5 palabras, lo mismo para fotos de lo mismo"}

            Como calificar:
            - calidad: luz, nitidez, encuadre y resolucion. 5 = foto profesional,
              3 = buena foto de telefono, 1 = casi inservible.
            - arreglable: true si lo que falla es luz, color, contraste o recorte
              (se corrige retocando). false si esta movida, desenfocada o pixelada.
            - fuerza: si esta foto SOLA detiene el scroll. 5 = el producto se ve
              clarisimo y antojable, 3 = correcta pero plana, 1 = no dice nada.
            - esArte: ya es una pieza terminada (flyer, banner, foto con texto o
              logo encima, captura con diseno).
            - necesitaTexto: el mensaje tiene que LEERSE en la imagen para
              funcionar: un precio, una oferta, una fecha, un evento, un
              lanzamiento. Una foto de producto sin promocion no lo necesita.
            - intencion: para que serviria publicarla.
            - orientacion: como esta tomada la foto (vertical = mas alta que ancha).
            - efimero: es del momento y pierde sentido en unos dias: la promocion
              de hoy, un evento en curso, detras de camaras, el dia a dia del
              local. Un producto bien fotografiado o el local no son efimeros.
            - tema: el objeto o asunto concreto ("depa Calle 60", "tacos al
              pastor", "evento aniversario"). Dos fotos del mismo producto o del
              mismo lugar llevan el mismo tema.

            VA: encaja con lo que el negocio vende o con su dia a dia (su
            producto, su local, su equipo, sus clientes, sus eventos).

            OBSERVACION (no decides tu, decide el dueno):
            - No esta claro que tenga que ver con el negocio: una foto personal,
              un meme, un paisaje, algo de otro tema.
            - Derechos de autor: marca de agua de un banco de imagenes o foto
              que parece bajada de internet.
            - Privacidad: menores de edad identificables, telefonos, chats,
              placas de coche, domicilios particulares.
            - Contenido regulado: alcohol, medicamentos, "antes y despues" de
              salud o estetica, promesas de rendimiento o de inversion.
            - Una promocion con fecha que ya paso.

            DESCARTADA: choca con la marca. Es de otro giro, es contenido de la
            competencia, o es algo que la marca pide evitar.

            Si la marca dice poco de si misma, nunca DESCARTADA: usa OBSERVACION.
            No inventes datos. El motivo habla de tu a tu con el dueno.
            """;

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public RevisorDeMarca(OpenAiClient client) {
        this.client = client;
    }

    /**
     * Revisa una foto. Nunca lanza: si la IA falla devuelve {@code null}, y el
     * agente la deja pendiente para la siguiente vuelta en vez de decidir a
     * ciegas.
     *
     * @param marcaCompleta si la sección de Marca está suficientemente llena
     *                      como para descartar con criterio
     */
    public Revision revisar(String url, Redactor.Negocio negocio, boolean marcaCompleta) {
        try {
            String respuesta = client.describeImages(AiOperacion.AGENTE_REVISAR, SISTEMA,
                    contexto(negocio, marcaCompleta), List.of(url));
            return interpretar(respuesta, marcaCompleta);
        } catch (Exception ex) {
            log.warn("El agente no pudo revisar {}: {}", url, ex.toString());
            return null;
        }
    }

    /** Lo que se le cuenta de la marca a la IA. Público: el Analista de video lo usa igual. */
    public static String contexto(Redactor.Negocio n, boolean marcaCompleta) {
        MarcaDelNegocio m = n.marca() == null ? MarcaDelNegocio.VACIA : n.marca();
        StringBuilder t = new StringBuilder("El negocio:\n");
        linea(t, "Nombre", n.nombre());
        linea(t, "Giro", n.giro());
        linea(t, "Ciudad", n.ciudad());
        linea(t, "Descripcion", n.descripcion());
        linea(t, "Que vende o destaca", m.queVende());
        linea(t, "A quien le habla", m.publico());
        linea(t, "Lo que pide evitar", m.evitar());
        if (!marcaCompleta) {
            t.append("La marca esta incompleta: ante la duda usa OBSERVACION, nunca DESCARTADA.\n");
        }
        t.append("\nRevisa esta foto.");
        return t.toString();
    }

    private static void linea(StringBuilder t, String etiqueta, String valor) {
        if (MarcaDelNegocio.hay(valor)) {
            t.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }

    /**
     * Del texto de la IA a una revisión. Tolera lo que suelen hacer los
     * modelos: envolver el JSON en texto o en un bloque de código.
     */
    Revision interpretar(String respuesta, boolean marcaCompleta) throws Exception {
        if (respuesta == null) {
            return null;
        }
        int inicio = respuesta.indexOf('{');
        int fin = respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            return null;
        }
        JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1));

        Veredicto veredicto;
        try {
            veredicto = Veredicto.valueOf(n.path("veredicto").asText("").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // Un veredicto que no se entiende no se adivina: que lo vea la persona.
            veredicto = Veredicto.OBSERVACION;
        }
        // La regla vale aunque la IA no la respete: sin marca no se descarta.
        if (veredicto == Veredicto.DESCARTADA && !marcaCompleta) {
            veredicto = Veredicto.OBSERVACION;
        }
        DecisorDelAgente.Intencion intencion;
        try {
            intencion = DecisorDelAgente.Intencion.valueOf(
                    n.path("intencion").asText("VENDER").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            intencion = DecisorDelAgente.Intencion.VENDER;
        }
        // Lo que no venga se asume como una foto correcta (3): ni la castiga ni la premia.
        DecisorDelAgente.Diagnostico diagnostico = new DecisorDelAgente.Diagnostico(
                n.path("calidad").asInt(3),
                recortar(n.path("queFalla").asText(""), 60),
                n.path("arreglable").asBoolean(true),
                n.path("fuerza").asInt(3),
                n.path("esArte").asBoolean(false),
                n.path("necesitaTexto").asBoolean(false),
                intencion,
                n.path("tipo").asText("OTRO").strip().toUpperCase(Locale.ROOT));
        String orientacion = n.path("orientacion").asText("CUADRADA").strip().toUpperCase(Locale.ROOT);
        if (!List.of("VERTICAL", "HORIZONTAL", "CUADRADA").contains(orientacion)) {
            orientacion = "CUADRADA";
        }
        return new Revision(veredicto,
                recortar(n.path("motivo").asText(""), 400),
                recortar(n.path("descripcion").asText(""), 900),
                recortar(n.path("idea").asText(""), 400),
                diagnostico,
                orientacion,
                n.path("efimero").asBoolean(false),
                recortar(n.path("tema").asText(""), 80));
    }

    // ------------------------------------------------------------ guardarla

    /**
     * La revisión como JSON, para guardarla en el archivo
     * ({@code MediaAsset.agenteAnalisis}) mientras espera a que se organice la
     * tanda. {@code forzada}: la persona dijo que va (no se vuelve a preguntar).
     */
    public static String aJson(Revision r, boolean forzada) {
        com.fasterxml.jackson.databind.node.ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("veredicto", r.veredicto().name());
        n.put("motivo", r.motivo());
        n.put("descripcion", r.descripcion());
        n.put("idea", r.idea());
        DecisorDelAgente.Diagnostico d = r.diagnostico();
        n.put("calidad", d.calidad());
        n.put("queFalla", d.queFalla());
        n.put("arreglable", d.arreglable());
        n.put("fuerza", d.fuerza());
        n.put("esArte", d.esArte());
        n.put("necesitaTexto", d.necesitaTexto());
        n.put("intencion", d.intencion().name());
        n.put("tipo", d.tipo());
        n.put("orientacion", r.orientacion());
        n.put("efimero", r.efimero());
        n.put("tema", r.tema());
        n.put("forzada", forzada);
        return n.toString();
    }

    /** Lo guardado con {@link #aJson}, o nulo si no se puede leer. */
    public static Revision deJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            // Se guardó ya validado: sin marca incompleta que corregir.
            return new RevisorDeMarca(null).interpretar(json, true);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Si al guardarla la persona ya había dicho que va. */
    public static boolean forzada(String json) {
        try {
            return json != null && new ObjectMapper().readTree(json).path("forzada").asBoolean(false);
        } catch (Exception ex) {
            return false;
        }
    }

    private static String recortar(String s, int max) {
        String limpio = s == null ? "" : s.strip();
        return limpio.length() <= max ? limpio : limpio.substring(0, max);
    }
}

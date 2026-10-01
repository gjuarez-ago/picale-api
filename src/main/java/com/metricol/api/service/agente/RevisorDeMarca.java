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
     * @param tipo        producto, lugar, equipo, evento, promoción, testimonio u otro
     * @param logo        si conviene ponerle el logo del negocio
     * @param porQueLogo  por qué sí o por qué no, para la persona
     */
    public record Revision(Veredicto veredicto, String motivo, String descripcion, String idea, String tipo,
            boolean logo, String porQueLogo) {

        /** Sin decisión de logo: las pruebas y lo que no la necesita. */
        public Revision(Veredicto veredicto, String motivo, String descripcion, String idea, String tipo) {
            this(veredicto, motivo, descripcion, idea, tipo, false, "");
        }
    }

    private static final String SISTEMA = """
            Eres el community manager de un negocio. Te llega una foto que el
            negocio subio y decides si se publica en sus redes.

            Contestas SOLO un JSON:
            {"veredicto": "VA" | "OBSERVACION" | "DESCARTADA",
             "motivo": "una frase para el dueno, en espanol, diciendo por que",
             "descripcion": "que se ve, concreto, una o dos frases",
             "idea": "que comunicarias con esta foto, en una frase, como encargo para quien escribe",
             "tipo": "PRODUCTO" | "LUGAR" | "EQUIPO" | "EVENTO" | "PROMOCION" | "TESTIMONIO" | "OTRO",
             "logo": true | false,
             "porQueLogo": "una frase corta: por que si o por que no lleva el logo"}

            LOGO: si en fotos de producto, promociones y piezas que alguien
            compartiria fuera de la cuenta, donde importa que se sepa de quien
            es. No en fotos del equipo, del local, de eventos o testimonios, ni
            en fotos que ya traen un logo o mucho texto encima.

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
            - Calidad: muy borrosa, muy oscura, texto ilegible.
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

    static String contexto(Redactor.Negocio n, boolean marcaCompleta) {
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
        return new Revision(veredicto,
                recortar(n.path("motivo").asText(""), 400),
                recortar(n.path("descripcion").asText(""), 900),
                recortar(n.path("idea").asText(""), 400),
                n.path("tipo").asText("OTRO").strip().toUpperCase(Locale.ROOT),
                n.path("logo").asBoolean(false),
                recortar(n.path("porQueLogo").asText(""), 200));
    }

    private static String recortar(String s, int max) {
        String limpio = s == null ? "" : s.strip();
        return limpio.length() <= max ? limpio : limpio.substring(0, max);
    }
}

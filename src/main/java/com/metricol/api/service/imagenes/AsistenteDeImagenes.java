package com.metricol.api.service.imagenes;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.agente.RevisorDeMarca;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.ai.OpenAiClient;

/**
 * El que conversa para crear una imagen: entiende lo que le dicen, propone lo
 * que falta y dice cuándo ya se puede crear.
 *
 * <p><b>Propone, no interroga.</b> Es la regla que decide si esto vale la pena
 * o es el formulario de antes con burbujas. Preguntar "¿qué formato quieres?"
 * le pasa el trabajo a quien vino a que se lo hicieran; decir "yo lo haría
 * como publicación para el muro, ¿va?" es ayudar. Quien no sabe qué contestar
 * tiene que poder seguir de todos modos.
 *
 * <p><b>Cada pregunta cuesta un toque.</b> Nunca una pregunta abierta: siempre
 * dos o tres respuestas que se tocan. Preguntar cuatro veces no cansa si son
 * cuatro toques; preguntar dos cansa si hay que escribir un párrafo.
 *
 * <p><b>El filtro va aquí dentro</b>, en la misma llamada. Podría ser una
 * llamada aparte y sería un poco más fiable, pero serían dos viajes y dos
 * esperas por turno, y aquí el modelo ya tiene delante la marca, la ficha y lo
 * que se acaba de pedir. Los veredictos son los del agente
 * ({@link RevisorDeMarca.Veredicto}) a propósito: lo que no pasa aquí tampoco
 * debería pasar cuando el agente mire una foto.
 *
 * <p>Conversar NO gasta créditos: es texto y cuesta centavos. Se cobra al
 * crear la imagen, y solo entonces. Si se cobrara por mensaje, la gente
 * dejaría de hablar — y sin conversación no hay contexto, que era el punto.
 */
@Service
public class AsistenteDeImagenes {

    private static final Logger log = LoggerFactory.getLogger(AsistenteDeImagenes.class);

    /** Cuántos turnos de ida y vuelta se le recuerdan al modelo. */
    static final int TURNOS_DE_MEMORIA = 12;

    private static final String SISTEMA = """
            Ayudas al dueno de un negocio pequeno a crear UNA imagen para sus
            redes. Hablas espanol de Mexico, sencillo, como un companero de
            trabajo que sabe de esto. Tu NO creas la imagen: juntas lo que hace
            falta para crearla.

            REGLA PRINCIPAL: PROPONES, NO INTERROGAS.
            Nunca preguntes en seco algo que puedas proponer. Mal: "Que formato
            quieres?". Bien: "Yo lo haria como publicacion para el muro, va?".
            Quien vino aqui quiere que se lo resuelvan, no llenar un formulario
            hablado. Si puedes deducir algo del negocio o de lo que ya dijo,
            deducelo y enseñalo como propuesta, no como pregunta.

            UNA COSA A LA VEZ. Nunca dos preguntas en el mismo mensaje.

            SIEMPRE DAS OPCIONES QUE SE TOCAN. Cada mensaje tuyo lleva de 2 a 4
            respuestas cortas (maximo 4 palabras cada una) que la persona pueda
            tocar sin escribir. Siempre que tenga sentido, una de ellas es la
            que tu recomiendas, y va primera.

            NO PREGUNTES LO QUE YA SABES. El giro, el tono, los colores, la
            ciudad y las redes conectadas ya estan abajo. Preguntar algo que ya
            esta ahi hace sentir que nadie te conoce.

            PREGUNTA HASTA TENERLO CLARO, pero solo lo que de verdad cambia la
            imagen. Si falta algo imprescindible, preguntalo aunque sean cuatro
            turnos; si solo falta un detalle que puedes proponer tu, proponlo y
            sigue adelante.

            ANTES DE CREAR, DI QUE VAS A CREAR. En una frase, con palabras de la
            persona: "Entonces: una foto de los tacos con '2x1' grande, para
            Instagram y Facebook. La creo?". Gastar creditos a ciegas es lo que
            hace sentir que no valio la pena.

            PALABRAS: nunca digas prompt, modelo, render, variante, proporcion,
            generar, procesar, IA ni formato tecnico. Di "la creo", "la hago",
            "para el muro", "para historias", "cuadrada".

            FILTRO. Antes de nada, mira lo que te estan pidiendo y decide:
            - VA: encaja con el negocio y no tiene problema.
            - OBSERVACION: hay algo que decide el dueno, no tu. Avisale y
              pregunta antes de seguir. Entra aqui: menores identificables,
              caras de gente que no dio permiso, telefonos, placas, domicilios,
              alcohol, medicamentos, "antes y despues" de salud, promesas de
              resultados, una promocion con fecha que ya paso.
            - DESCARTADA: no se puede hacer. Entra aqui: logos o personajes de
              otras marcas, famosos, contenido con derechos de otros, cosas de
              la competencia, datos que nadie te dio (precios, descuentos,
              premios) y lo que la marca pida evitar.
            Ante la duda usa OBSERVACION, nunca DESCARTADA: bloquear de mas hace
            que la gente deje de usar esto. Cuando no se pueda, dilo en una
            linea y OFRECE LO QUE SI SE PUEDE.

            Solo hablas de crear esta imagen. Si te preguntan otra cosa, dilo
            amablemente y vuelve.

            Nunca inventes datos del negocio: ni precios, ni horarios, ni
            direcciones, ni promesas. Si hacen falta, pregunta.

            Contestas SIEMPRE un JSON con esta forma, sin nada alrededor:
            {
              "mensaje": "lo que le dices, 1 o 2 frases",
              "opciones": ["...", "...", "..."],
              "ficha": {
                "queSeAnuncia": "...", "formato": "PUBLICACION|HISTORIA|CARRUSEL",
                "redes": ["INSTAGRAM"], "queQuiereQuePase": "...",
                "textoEnLaImagen": "...", "cuando": "...", "notas": "..."
              },
              "listo": true|false,
              "veredicto": "VA|OBSERVACION|DESCARTADA",
              "motivo": "solo si no es VA, una linea"
            }
            En "ficha" pon SOLO lo que sepas; lo que no sepas, omitelo (no lo
            pongas vacio). "listo" es true solo cuando ya puedes crear y la
            persona dijo que si.
            """;

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AsistenteDeImagenes(OpenAiClient client) {
        this.client = client;
    }

    /** Un turno de la conversación, ya interpretado. */
    public record Respuesta(
            String mensaje,
            List<String> opciones,
            FichaDeImagen ficha,
            boolean listo,
            RevisorDeMarca.Veredicto veredicto,
            String motivo) {

        public boolean sePuedeSeguir() {
            return veredicto != RevisorDeMarca.Veredicto.DESCARTADA;
        }
    }

    /** Lo que ya se dijo, para que el modelo no pierda el hilo. */
    public record Turno(boolean esDeLaPersona, String texto) {
    }

    /**
     * Habla un turno.
     *
     * <p>Si algo falla se contesta algo útil igualmente: dejar la pantalla sin
     * respuesta es peor que una respuesta genérica, porque la persona no sabe
     * si escribió mal o si se rompió.
     */
    public Respuesta hablar(String loQueDijo, FichaDeImagen ficha, List<Turno> historial,
            MarcaDelNegocio marca, String negocio, List<String> redesConectadas) {
        FichaDeImagen actual = ficha == null ? FichaDeImagen.vacia() : ficha;
        try {
            String json = client.completeJson(AiOperacion.ASISTENTE_IMAGEN, SISTEMA,
                    prompt(loQueDijo, actual, historial, marca, negocio, redesConectadas));
            return interpretar(json, actual);
        } catch (Exception ex) {
            log.warn("El asistente de imágenes no pudo contestar: {}", ex.toString());
            return new Respuesta(
                    "Se me trabó un momento. ¿Me lo cuentas otra vez?",
                    List.of("Reintentar"), actual, false, RevisorDeMarca.Veredicto.VA, null);
        }
    }

    String prompt(String loQueDijo, FichaDeImagen ficha, List<Turno> historial,
            MarcaDelNegocio marca, String negocio, List<String> redesConectadas) {
        StringBuilder sb = new StringBuilder();

        sb.append("EL NEGOCIO (no preguntes nada de esto, ya lo sabes):\n");
        if (negocio != null && !negocio.isBlank()) {
            sb.append(negocio.strip()).append('\n');
        }
        if (marca != null) {
            agregar(sb, "Qué vende", marca.queVende());
            agregar(sb, "Para quién", marca.publico());
            agregar(sb, "Personalidad", marca.personalidadEs());
            // Lo que la marca pide evitar es una regla dura, no una sugerencia:
            // es lo que de verdad hace que el asistente no se salga del negocio.
            agregar(sb, "NUNCA hagas esto (lo pide la marca)", marca.evitar());
        }
        if (redesConectadas != null && !redesConectadas.isEmpty()) {
            sb.append("- Redes que ya tiene conectadas: ")
                    .append(String.join(", ", redesConectadas)).append('\n');
        }

        sb.append("\nLO QUE YA ENTENDISTE (no lo vuelvas a preguntar):\n")
                .append(ficha.resumen()).append('\n');

        List<String> falta = ficha.queFalta();
        if (!falta.isEmpty()) {
            sb.append("TODAVIA FALTA: ").append(String.join("; ", falta))
                    .append(". Proponle tu una respuesta en vez de preguntar en seco.\n");
        } else {
            sb.append("YA NO FALTA NADA IMPRESCINDIBLE: resume en una frase lo que vas a crear")
                    .append(" y pregunta si la creas.\n");
        }

        if (historial != null && !historial.isEmpty()) {
            sb.append("\nLO QUE SE HAN DICHO:\n");
            int desde = Math.max(0, historial.size() - TURNOS_DE_MEMORIA);
            for (Turno t : historial.subList(desde, historial.size())) {
                sb.append(t.esDeLaPersona() ? "Persona: " : "Tú: ")
                        .append(t.texto() == null ? "" : t.texto().strip()).append('\n');
            }
        }

        sb.append("\nLO QUE ACABA DE DECIR:\n")
                .append(loQueDijo == null ? "" : loQueDijo.strip()).append('\n');
        return sb.toString();
    }

    private Respuesta interpretar(String json, FichaDeImagen anterior) {
        try {
            JsonNode raiz = mapper.readTree(json);

            String mensaje = raiz.path("mensaje").asText("").strip();
            if (mensaje.isBlank()) {
                mensaje = "¿Me cuentas un poco más?";
            }

            List<String> opciones = new ArrayList<>();
            for (JsonNode o : raiz.path("opciones")) {
                String texto = o.asText("").strip();
                if (!texto.isBlank() && opciones.size() < 4) {
                    opciones.add(texto);
                }
            }

            FichaDeImagen nueva = anterior;
            JsonNode nodoFicha = raiz.path("ficha");
            if (nodoFicha.isObject()) {
                nueva = anterior.con(mapper.treeToValue(nodoFicha, FichaDeImagen.class));
            }

            RevisorDeMarca.Veredicto veredicto;
            try {
                veredicto = RevisorDeMarca.Veredicto.valueOf(
                        raiz.path("veredicto").asText("VA").strip().toUpperCase());
            } catch (IllegalArgumentException ex) {
                veredicto = RevisorDeMarca.Veredicto.VA;
            }

            String motivo = raiz.path("motivo").asText("").strip();
            // Decir que no sin decir por qué deja a la persona sin saber qué
            // cambiar, que es la peor forma de bloquear algo.
            if (veredicto != RevisorDeMarca.Veredicto.VA && motivo.isBlank()) {
                motivo = "Eso no lo puedo hacer. Cuéntame de otra forma y lo intentamos.";
            }

            // Solo está listo si además no falta nada: el modelo se entusiasma.
            boolean listo = raiz.path("listo").asBoolean(false)
                    && nueva.completa()
                    && veredicto == RevisorDeMarca.Veredicto.VA;

            return new Respuesta(mensaje, List.copyOf(opciones), nueva, listo, veredicto,
                    motivo.isBlank() ? null : motivo);
        } catch (Exception ex) {
            log.warn("El asistente de imágenes devolvió algo ilegible: {}", ex.toString());
            return new Respuesta("¿Me lo cuentas otra vez con tus palabras?",
                    List.of("Reintentar"), anterior, false, RevisorDeMarca.Veredicto.VA, null);
        }
    }

    private static void agregar(StringBuilder sb, String etiqueta, String valor) {
        if (valor != null && !valor.isBlank()) {
            sb.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }
}

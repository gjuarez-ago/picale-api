package com.metricol.api.service.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.OpenAiProperties;

/**
 * El director de arte de las campañas de imagen.
 *
 * <p>No dibuja: mira las fotos reales del negocio y escribe el PLAN que después
 * sigue el modelo de imágenes —qué foto manda, qué composición, cómo es la
 * escena y qué texto lleva—. Separar decidir de dibujar es lo que hace la
 * diferencia: el modelo de imágenes es bueno ejecutando una instrucción clara
 * y mediocre decidiendo qué instrucción darse, y lo segundo es trabajo de un
 * modelo de lenguaje que sí ve las fotos.
 *
 * <p><b>Devuelve una respuesta cerrada.</b> La composición es una de tres
 * conocidas ({@link #LAYOUTS}) y los textos llegan ya acotados y limpios: lo
 * que salga de aquí acaba entre comillas dentro de otro prompt, y un modelo
 * que se pone creativo con la estructura rompe todo lo que viene después.
 *
 * <p><b>Nunca tumba una campaña.</b> Si el modelo no está permitido, tarda más
 * de lo que se le da, contesta algo que no es un plan o simplemente falla, se
 * devuelve vacío y quien llama sigue con el prompt de siempre. El director es
 * una mejora, no una condición.
 */
@Component
public class ArtDirector {

    private static final Logger log = LoggerFactory.getLogger(ArtDirector.class);

    /** Las composiciones que sabe pedir el prompt de imagen. Ver {@code PromptDeImagen}. */
    public static final Set<String> LAYOUTS = Set.of("photo_bottom_band", "photo_top_title", "framed_photo");

    public static final String LAYOUT_POR_DEFECTO = "photo_bottom_band";

    /** Todo lo que el director necesita saber. Los textos ya vienen sin datos de otros workspaces. */
    public record Contexto(
            String negocio,
            String giro,
            String ciudad,
            String descripcion,
            String objetivoNegocio,
            String idea,
            String objetivoCampana,
            String tono,
            String estilo,
            String cta,
            List<String> paleta,
            List<String> captionsAnteriores,
            String formato,
            List<String> fotoUrls,
            List<String> redes) {
    }

    /**
     * El plan. {@code fotoProtagonista} va de 1 en adelante (0 = sin preferencia).
     * {@code cta} puede venir vacío: la persona ya escribió el suyo y ese manda.
     * {@code captionsPorRed} lleva un texto por cada red pedida (clave = nombre de la red en
     * mayúsculas); puede faltar alguna, y entonces vale {@code caption}.
     */
    public record Brief(
            String layout,
            int fotoProtagonista,
            String escena,
            String titular,
            String subtitulo,
            String cta,
            String caption,
            Map<String, String> captionsPorRed) {
    }

    private static final String SISTEMA = """
            You are the art director of a social-media agency for small Mexican businesses. You do NOT draw.
            You write the brief that an image-generation model will follow to compose ONE finished, professional
            social-media ad from the business's REAL photos.

            Reply with ONLY a JSON object with exactly these keys:
            "layout": one of "photo_bottom_band", "photo_top_title", "framed_photo".
            "hero_photo": the number (starting at 1) of the photo that should lead, or 0 if none is clearly better.
            "scene": 2-4 sentences IN ENGLISH about lighting, subtle natural color grade, background treatment and
              mood. Never mention text, logos, buttons or layout: those are added separately.
            "headline": SPANISH (Mexico), at most 7 words, correct accents, concrete, no clichés.
            "subtitle": SPANISH, at most 8 words (city or a real differentiator), or "".
            "cta": SPANISH, at most 4 words, or "".
            "caption": SPANISH, 2-4 short lines, at most 2 emojis, no hashtags, ends with the call to action.
            "captions": an object with one caption per requested network, using exactly the network names given
              (for example "INSTAGRAM"). Tailor each: Instagram is warm and visual, up to 2 emojis; Facebook is
              conversational and can be a little longer; LinkedIn is professional and concrete, no emojis. Each one
              is SPANISH, 2-4 short lines, no hashtags, and ends with the call to action. Omit it if no networks
              were requested.

            Layouts:
            - photo_bottom_band: the photo fills the frame and a solid brand-color panel at the bottom holds the
              text. Choose it when the subject sits in the upper two thirds of the photo.
            - photo_top_title: the photo fills the frame and the title sits on a soft gradient at the top. Choose
              it when the top of the photo is calm (sky, wall) and the subject sits lower.
            - framed_photo: brand-color background with the photo inside a rounded frame and the text on plain
              color. Choose it when the photo is busy everywhere and text over it would hurt legibility.

            Rules:
            - The real photos are the hero. Never suggest replacing, restyling or inventing people, equipment or places.
            - Never invent facts: no prices, phone numbers, years, certifications or claims the business did not give.
            - Match the voice of the business's previous captions when they are provided.
            - Keep the copy short: it will be rendered as large text on the image.
            """;

    private final OpenAiProperties props;
    private final AiUsageRecorder usos;
    private final RestClient restClient;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Si OpenAI rechazó {@code reasoning_effort} una vez, no se vuelve a mandar
     * en esta vida del proceso: los modelos que no razonan lo rechazan, y un
     * ajuste de velocidad no puede quitarle el director a todas las campañas.
     */
    private volatile boolean razonamientoSoportado = true;

    /**
     * El modelo del director no está permitido en el proyecto de OpenAI: desde
     * entonces se va directo al de respaldo, sin gastar un intento (y un segundo)
     * en un 403 que se sabe que se repetirá.
     */
    private volatile boolean principalSinAcceso = false;

    @Autowired
    public ArtDirector(OpenAiProperties props, AiUsageRecorder usos) {
        this(props, usos, construirRestClient(props));
    }

    /** Para las pruebas, que le ponen un servidor falso. */
    ArtDirector(OpenAiProperties props, AiUsageRecorder usos, RestClient restClient) {
        this.props = props;
        this.usos = usos;
        this.restClient = restClient;
    }

    private static RestClient construirRestClient(OpenAiProperties props) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(5));
        fabrica.setReadTimeout(Duration.ofSeconds(Math.max(5, props.getDirectorTimeoutSeconds())));
        return RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(fabrica)
                .build();
    }

    public boolean disponible() {
        return props.isConfigured() && props.getDirectorModel() != null && !props.getDirectorModel().isBlank();
    }

    /** El plan de la campaña, o vacío si el director no pudo (la campaña sigue sin él). */
    public Optional<Brief> dirigir(Contexto contexto) {
        if (!disponible()) {
            return Optional.empty();
        }
        long inicio = System.currentTimeMillis();
        String modelo = principalSinAcceso ? respaldo() : props.getDirectorModel();
        try {
            Map<?, ?> respuesta;
            try {
                respuesta = pedirConReintentos(contexto, modelo);
            } catch (RestClientResponseException ex) {
                // El proyecto de OpenAI no tiene el modelo del director: es lo que pasó con
                // gpt-5.5 y dejó al director apagado sin que nadie lo notara. Se sigue con el
                // modelo de texto, que también ve fotos y devuelve JSON: un director menos
                // capaz es mejor que ninguno, y ninguno es lo que produce anuncios genéricos.
                if (!principalSinAcceso && sinAcceso(ex) && !respaldo().isBlank() && !respaldo().equals(modelo)) {
                    principalSinAcceso = true;
                    log.error("El proyecto de OpenAI no tiene acceso al modelo del director ({}). "
                            + "Se usa {}. Arréglalo permitiéndolo en OpenAI o poniendo un modelo permitido en "
                            + "OPENAI_DIRECTOR_MODEL.", modelo, respaldo());
                    modelo = respaldo();
                    respuesta = pedirConReintentos(contexto, modelo);
                } else {
                    throw ex;
                }
            }

            anotarGasto(respuesta, modelo);
            Optional<Brief> plan = leerPlan(respuesta, contexto.fotoUrls().size());
            log.info("Director de arte ({}): {} ms, {}", modelo,
                    System.currentTimeMillis() - inicio,
                    plan.map(b -> "layout=" + b.layout() + " heroe=" + b.fotoProtagonista())
                            .orElse("sin plan utilizable"));
            return plan;
        } catch (RestClientResponseException ex) {
            log.warn("El director de arte falló ({} en {} ms): {}", ex.getStatusCode().value(),
                    System.currentTimeMillis() - inicio, recortar(ex.getResponseBodyAsString()));
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("El director de arte falló tras {} ms: {}", System.currentTimeMillis() - inicio, ex.toString());
            return Optional.empty();
        }
    }

    /** Se le puede pedir razonamiento solo al modelo del director: el de texto no razona y lo rechaza. */
    private Map<?, ?> pedirConReintentos(Contexto contexto, String modelo) {
        String esfuerzo = props.getDirectorReasoningEffort();
        boolean esElDelDirector = modelo.equals(props.getDirectorModel());
        boolean conEsfuerzo = esElDelDirector && razonamientoSoportado && esfuerzo != null && !esfuerzo.isBlank();
        try {
            return pedir(contexto, modelo, conEsfuerzo ? esfuerzo.trim() : null);
        } catch (RestClientResponseException ex) {
            if (conEsfuerzo && ex.getStatusCode().value() == 400
                    && ex.getResponseBodyAsString().contains("reasoning_effort")) {
                log.warn("El modelo del director no acepta reasoning_effort: se sigue sin él. {}",
                        recortar(ex.getResponseBodyAsString()));
                razonamientoSoportado = false;
                return pedir(contexto, modelo, null);
            }
            throw ex;
        }
    }

    /** OpenAI contesta 403 (o 404) con {@code model_not_found} cuando el proyecto no tiene el modelo. */
    private static boolean sinAcceso(RestClientResponseException ex) {
        int estado = ex.getStatusCode().value();
        String cuerpo = ex.getResponseBodyAsString();
        return (estado == 403 || estado == 404)
                && (cuerpo.contains("model_not_found") || cuerpo.contains("does not have access"));
    }

    /** El modelo de texto de siempre, que ya está permitido en el proyecto. */
    private String respaldo() {
        String modelo = props.getModel();
        return modelo == null ? "" : modelo.trim();
    }

    private Map<?, ?> pedir(Contexto c, String modelo, String esfuerzo) {
        List<Map<String, Object>> partes = new ArrayList<>();
        partes.add(Map.of("type", "text", "text", pedidoDelUsuario(c)));
        for (String url : c.fotoUrls()) {
            // "low": para decidir composición no hace falta leer la letra chica,
            // y baja el costo y el tiempo de cada foto.
            partes.add(Map.of("type", "image_url", "image_url", Map.of("url", url, "detail", "low")));
        }

        // Sin temperature ni max_tokens a propósito: los modelos que razonan
        // rechazan la primera con cualquier valor que no sea el de fábrica, y
        // la segunda tiene otro nombre en ellos.
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("model", modelo);
        cuerpo.put("messages", List.of(
                Map.of("role", "system", "content", SISTEMA),
                Map.of("role", "user", "content", partes)));
        cuerpo.put("response_format", Map.of("type", "json_object"));
        if (esfuerzo != null) {
            cuerpo.put("reasoning_effort", esfuerzo);
        }

        return restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(Map.class);
    }

    static String pedidoDelUsuario(Contexto c) {
        StringBuilder t = new StringBuilder();
        t.append("Business: ").append(valor(c.negocio(), "a local business"));
        if (hay(c.giro())) {
            t.append(" (").append(c.giro().trim()).append(")");
        }
        if (hay(c.ciudad())) {
            t.append(", ").append(c.ciudad().trim());
        }
        t.append("\n");
        if (hay(c.descripcion())) {
            t.append("About the business: ").append(c.descripcion().trim()).append("\n");
        }
        if (hay(c.objetivoNegocio())) {
            t.append("What the business wants from social media: ").append(c.objetivoNegocio().trim()).append("\n");
        }
        t.append("Format: ").append(valor(c.formato(), "post")).append("\n");
        t.append("What this post must communicate (the owner's own words): ").append(valor(c.idea(), "")).append("\n");
        if (hay(c.objetivoCampana())) {
            t.append("Goal of the post: ").append(c.objetivoCampana().trim()).append("\n");
        }
        if (hay(c.tono())) {
            t.append("Tone: ").append(c.tono().trim()).append("\n");
        }
        if (hay(c.estilo())) {
            t.append("Visual style: ").append(c.estilo().trim()).append("\n");
        }
        if (hay(c.cta())) {
            t.append("The owner's call to action (it will be used as written): ").append(c.cta().trim()).append("\n");
        }
        if (c.paleta() != null && !c.paleta().isEmpty()) {
            t.append("Brand colors, read from the logo: ").append(String.join(", ", c.paleta())).append("\n");
        }
        if (c.captionsAnteriores() != null && !c.captionsAnteriores().isEmpty()) {
            t.append("Captions this business already published (match their voice, do not repeat them):\n");
            c.captionsAnteriores().forEach(caption -> t.append("- ").append(caption).append("\n"));
        }
        if (c.redes() != null && !c.redes().isEmpty()) {
            t.append("Networks to write captions for: ").append(String.join(", ", c.redes())).append("\n");
        }
        int fotos = c.fotoUrls().size();
        t.append(fotos == 0
                ? "There are no photos: design the scene from the description."
                : "The " + fotos + " attached photo(s) are numbered in the order given, starting at 1.");
        return t.toString();
    }

    private Optional<Brief> leerPlan(Map<?, ?> respuesta, int fotos) throws Exception {
        if (respuesta == null || !(respuesta.get("choices") instanceof List<?> opciones) || opciones.isEmpty()
                || !(opciones.get(0) instanceof Map<?, ?> primera)
                || !(primera.get("message") instanceof Map<?, ?> mensaje)
                || !(mensaje.get("content") instanceof String contenido) || contenido.isBlank()) {
            return Optional.empty();
        }

        JsonNode nodo = json.readTree(contenido);
        String titular = limpio(nodo.path("headline").asText(""), 70);
        if (titular.isBlank()) {
            // Sin titular no hay anuncio: mejor el camino de siempre que uno vacío.
            return Optional.empty();
        }

        String layout = nodo.path("layout").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        if (!LAYOUTS.contains(layout)) {
            layout = LAYOUT_POR_DEFECTO;
        }
        int heroe = nodo.path("hero_photo").asInt(0);
        if (heroe < 0 || heroe > fotos) {
            heroe = 0;
        }

        return Optional.of(new Brief(
                layout,
                heroe,
                limpio(nodo.path("scene").asText(""), 700),
                titular,
                limpio(nodo.path("subtitle").asText(""), 70),
                limpio(nodo.path("cta").asText(""), 40),
                parrafos(nodo.path("caption").asText(""), 600),
                captionsPorRed(nodo.path("captions"))));
    }

    /** Un texto por red, con las claves en mayúsculas; lo vacío se descarta. */
    private static Map<String, String> captionsPorRed(JsonNode nodo) {
        Map<String, String> captions = new LinkedHashMap<>();
        if (nodo != null && nodo.isObject()) {
            nodo.fields().forEachRemaining(e -> {
                String texto = parrafos(e.getValue().asText(""), 600);
                if (!texto.isBlank()) {
                    captions.put(e.getKey().trim().toUpperCase(java.util.Locale.ROOT), texto);
                }
            });
        }
        return captions;
    }

    private void anotarGasto(Map<?, ?> respuesta, String pedido) {
        try {
            if (respuesta != null && respuesta.get("usage") instanceof Map<?, ?> uso) {
                Object modelo = respuesta.get("model");
                String nombre = modelo instanceof String n && !n.isBlank() ? n : pedido;
                if (pedido.equals(props.getDirectorModel())) {
                    usos.registrarDirector(nombre, entero(uso.get("prompt_tokens")),
                            entero(uso.get("completion_tokens")));
                } else {
                    // El de respaldo es el modelo de texto: se cobra a SU precio, no al del director.
                    usos.registrarDirectorConPrecioDeTexto(nombre, entero(uso.get("prompt_tokens")),
                            entero(uso.get("completion_tokens")));
                }
            }
        } catch (Exception ex) {
            // Perder una fila del reporte no debe tirar un plan que ya se pagó.
            log.warn("No se pudo anotar el gasto del director: {}", ex.toString());
        }
    }

    /**
     * Una sola línea, sin comillas ni barras: el texto acaba entre comillas
     * dentro de otro prompt y unas comillas de más lo romperían.
     */
    public static String limpio(String texto, int maximo) {
        if (texto == null) {
            return "";
        }
        // Primero se quitan comillas y barras y DESPUÉS se colapsan los espacios:
        // al revés, una barra entre dos espacios dejaba un espacio doble.
        String plano = texto.replace("\"", "").replace("\\", "").replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return plano.length() <= maximo ? plano : plano.substring(0, maximo).trim();
    }

    /** Igual, pero conservando los saltos de línea: es el caption, que va en varias líneas. */
    static String parrafos(String texto, int maximo) {
        if (texto == null) {
            return "";
        }
        String limpio = texto.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo).trim();
    }

    private static boolean hay(String texto) {
        return texto != null && !texto.isBlank();
    }

    private static String valor(String texto, String porDefecto) {
        return hay(texto) ? texto.trim() : porDefecto;
    }

    private static int entero(Object valor) {
        return valor instanceof Number numero ? numero.intValue() : 0;
    }

    private static String recortar(String texto) {
        return texto.length() <= 300 ? texto : texto.substring(0, 300) + "...";
    }
}

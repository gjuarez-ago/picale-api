package com.metricol.api.service.ai;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.metricol.api.config.OpenAiProperties;

/**
 * Cliente delgado sobre la API de imágenes de OpenAI (gpt-image).
 *
 * <p>Dos caminos, según haya fotos de referencia: {@link #generar} para una
 * imagen a partir de solo texto y {@link #editar} cuando hay fotos del
 * producto que deben verse en el resultado.
 *
 * <p><b>No anota el gasto.</b> Devuelve los tokens y quien llama los registra.
 * Las imágenes de un carrusel se piden en paralelo, en hilos que no tienen el
 * workspace de la petición, y anotar desde ahí las dejaría sin dueño en el
 * reporte de gasto. Ver {@code CampaignImageService}.
 */
@Component
public class OpenAiImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);

    /** Los lienzos que acepta gpt-image. Cualquier otro valor se sustituye por el vertical. */
    private static final Set<String> TAMANOS = Set.of("1024x1024", "1024x1536", "1536x1024");

    public static final String TAMANO_VERTICAL = "1024x1536";

    /** Una foto de referencia ya descargada. */
    public record Referencia(byte[] bytes, String nombre, String contentType) {
    }

    /** Lo que devolvió OpenAI: la imagen (PNG, JPEG o WebP) y lo que cobró. */
    public record Resultado(byte[] imagen, int tokensEntrada, int tokensSalida, String modelo) {
    }

    private final OpenAiProperties props;
    private final RestClient restClient;

    // Con dos constructores Spring no adivina cuál usar: este es el suyo.
    @Autowired
    public OpenAiImageClient(OpenAiProperties props) {
        this(props, construirRestClient());
    }

    /** Para las pruebas, que le ponen un servidor falso al cliente. */
    OpenAiImageClient(OpenAiProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    private static RestClient construirRestClient() {
        // Una imagen tarda de 20 a 80 segundos. Con tiempos explícitos: una
        // llamada colgada dejaría tomado el hilo de quien espera mirando la
        // pantalla. Cloudflare corta a los 100 s, así que pasar de ahí no
        // sirve de nada.
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(10));
        fabrica.setReadTimeout(Duration.ofSeconds(110));
        return RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(fabrica)
                .build();
    }

    public boolean disponible() {
        return props.isConfigured();
    }

    /** Una imagen a partir de solo texto. */
    public Resultado generar(String prompt, String tamano) {
        exigirConfiguracion();
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("model", props.getImageModel());
        cuerpo.put("prompt", prompt);
        cuerpo.put("size", tamanoValido(tamano));
        cuerpo.put("quality", "medium");
        cuerpo.put("n", 1);

        try {
            Map<?, ?> respuesta = restClient.post()
                    .uri("/images/generations")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .body(Map.class);
            return leer(respuesta);
        } catch (RestClientResponseException ex) {
            throw traducir(ex);
        } catch (ResourceAccessException ex) {
            throw sinRespuesta(ex);
        }
    }

    /**
     * Si OpenAI rechazó {@code input_fidelity} una vez, no se vuelve a mandar en
     * esta vida del proceso: el parámetro es un ajuste de calidad, no una
     * condición para generar, y no debe poder tumbar todas las ediciones.
     */
    private volatile boolean fidelidadSoportada = true;

    /** Una imagen que parte de las fotos de referencia. */
    public Resultado editar(String prompt, List<Referencia> referencias, String tamano) {
        exigirConfiguracion();
        String fidelidad = props.getImageInputFidelity();
        boolean conFidelidad = fidelidadSoportada && fidelidad != null && !fidelidad.isBlank();

        try {
            return enviarEdicion(prompt, referencias, tamano, conFidelidad ? fidelidad.trim() : null);
        } catch (RestClientResponseException ex) {
            if (conFidelidad && ex.getStatusCode().value() == 400
                    && ex.getResponseBodyAsString().contains("input_fidelity")) {
                log.warn("El modelo de imágenes no acepta input_fidelity: se sigue sin él. {}",
                        recortar(ex.getResponseBodyAsString()));
                fidelidadSoportada = false;
                try {
                    return enviarEdicion(prompt, referencias, tamano, null);
                } catch (RestClientResponseException segundo) {
                    throw traducir(segundo);
                } catch (ResourceAccessException segundo) {
                    throw sinRespuesta(segundo);
                }
            }
            throw traducir(ex);
        } catch (ResourceAccessException ex) {
            throw sinRespuesta(ex);
        }
    }

    private Resultado enviarEdicion(String prompt, List<Referencia> referencias, String tamano, String fidelidad) {
        MultipartBodyBuilder cuerpo = new MultipartBodyBuilder();
        cuerpo.part("model", props.getImageModel());
        cuerpo.part("prompt", prompt);
        cuerpo.part("size", tamanoValido(tamano));
        cuerpo.part("quality", "medium");
        cuerpo.part("n", "1");
        if (fidelidad != null) {
            cuerpo.part("input_fidelity", fidelidad);
        }
        for (Referencia foto : referencias) {
            cuerpo.part("image[]", new ByteArrayResource(foto.bytes()) {
                @Override
                public String getFilename() {
                    return foto.nombre();
                }
            }).contentType(MediaType.parseMediaType(foto.contentType()));
        }

        Map<?, ?> respuesta = restClient.post()
                .uri("/images/edits")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(cuerpo.build())
                .retrieve()
                .body(Map.class);
        return leer(respuesta);
    }

    private void exigirConfiguracion() {
        if (!props.isConfigured()) {
            throw new IllegalStateException("La generación de imágenes no está disponible por ahora.");
        }
    }

    static String tamanoValido(String pedido) {
        return pedido != null && TAMANOS.contains(pedido.trim()) ? pedido.trim() : TAMANO_VERTICAL;
    }

    private Resultado leer(Map<?, ?> respuesta) {
        Object datos = respuesta == null ? null : respuesta.get("data");
        if (!(datos instanceof List<?> lista) || lista.isEmpty() || !(lista.get(0) instanceof Map<?, ?> primera)
                || !(primera.get("b64_json") instanceof String base64) || base64.isBlank()) {
            throw new IllegalStateException("La IA no devolvió ninguna imagen. Intenta de nuevo.");
        }

        int entrada = 0;
        int salida = 0;
        if (respuesta.get("usage") instanceof Map<?, ?> uso) {
            entrada = entero(uso.get("input_tokens"));
            salida = entero(uso.get("output_tokens"));
        }
        return new Resultado(Base64.getMimeDecoder().decode(base64), entrada, salida, props.getImageModel());
    }

    private static int entero(Object valor) {
        return valor instanceof Number numero ? numero.intValue() : 0;
    }

    /**
     * Del error de OpenAI a algo que la persona pueda leer.
     *
     * <p>Lo que es culpa del contenido (bloqueado por las políticas) se dice
     * tal cual, porque cambiando el texto o la foto se arregla. Todo lo demás
     * —llave, modelo no permitido, cuenta sin saldo— no es asunto de quien
     * está creando una campaña: se registra completo y a ella le llega un
     * mensaje genérico.
     */
    private static RuntimeException traducir(RestClientResponseException ex) {
        String cuerpo = ex.getResponseBodyAsString();
        HttpStatusCode estado = ex.getStatusCode();

        if (cuerpo.contains("moderation_blocked") || cuerpo.contains("content_policy")
                || cuerpo.contains("safety system")) {
            return new IllegalArgumentException(
                    "La IA no pudo crear esa imagen por sus políticas de contenido. "
                            + "Cambia el texto o las fotos e inténtalo de nuevo.");
        }
        if (estado.value() == 429) {
            log.warn("OpenAI imágenes: 429 {}", recortar(cuerpo));
            return new IllegalStateException(
                    "La IA está atendiendo muchas peticiones. Inténtalo de nuevo en un minuto.");
        }
        log.error("OpenAI imágenes: {} {}", estado.value(), recortar(cuerpo));
        return new IllegalStateException("No se pudo generar la imagen. Inténtalo de nuevo.");
    }

    private static RuntimeException sinRespuesta(ResourceAccessException ex) {
        log.warn("OpenAI imágenes sin respuesta: {}", ex.getMessage());
        return new IllegalStateException("La IA tardó demasiado en responder. Inténtalo de nuevo.");
    }

    private static String recortar(String texto) {
        return texto.length() <= 300 ? texto : texto.substring(0, 300) + "...";
    }
}

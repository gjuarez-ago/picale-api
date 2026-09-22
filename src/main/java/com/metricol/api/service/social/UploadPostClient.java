package com.metricol.api.service.social;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.enums.PostFormat;

/**
 * Cliente delgado sobre la API de upload-post.com (https://docs.upload-post.com).
 * Solo sabe hablar HTTP con ellos; quién arma el request a partir de un Post
 * de metricol es {@link UploadPostPublisher}.
 *
 * OJO: el formato exacto de la respuesta (éxito/error por plataforma) no
 * está del todo documentado públicamente — este cliente devuelve el JSON
 * crudo como Map para que el publisher lo interprete de forma tolerante.
 * Conviene confirmar el shape real con una llamada de prueba en cuanto haya
 * una API key válida.
 */
@Component
public class UploadPostClient {

    private final UploadPostProperties props;
    private final RestClient restClient;
    /**
     * Sin timeout, un medio que no responde deja el worker colgado para
     * siempre y ese hilo no vuelve a publicar nada: con ocho workers bastan
     * ocho archivos lentos para parar la cola entera.
     */
    private final HttpClient downloader = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public UploadPostClient(UploadPostProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Apikey " + props.getApiKey())
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publishText(String user, List<String> platforms, String title) {
        MultiValueMap<String, Object> body = baseFields(user, platforms);
        body.add("title", title);

        return restClient.post()
                .uri("/upload_text")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Publica de una a varias fotos como UNA sola publicación (carrusel).
     *
     * <p>Todas las imágenes van en el mismo request, repitiendo el campo
     * {@code photos[]}. Mandarlas de a una habría creado varias publicaciones
     * sueltas en la red en vez de un carrusel, que es justo lo contrario de
     * lo que pide quien elige seis fotos.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> publishPhotos(
            String user, List<String> platforms, String caption,
            Map<String, String> captionsPorRed, List<String> photoUrls, PostFormat formato) {
        MultiValueMap<String, Object> body = cuerpoFotos(user, platforms, caption, captionsPorRed, formato);
        // El orden importa: es el que verá quien deslice el carrusel, y es el
        // que la persona eligió en la pantalla de captura.
        photoUrls.forEach(url -> body.add("photos[]", download(url)));

        return restClient.post()
                .uri("/upload_photos")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publishVideo(String user, List<String> platforms, String title,
            Map<String, String> captionsPorRed, String videoUrl, PostFormat formato) {
        MultiValueMap<String, Object> body = baseFields(user, platforms);
        body.add("title", title);
        body.add("description", title);
        textosPorRed(body, captionsPorRed, true);
        formatoPorRed(body, platforms, formato);
        body.add("video", download(videoUrl));

        return restClient.post()
                .uri("/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Cómo acabó un envío, red por red.
     *
     * <p>Esta llamada es la que dice la verdad. {@code /upload} solo acepta el
     * encargo —{@code is_async: true} en todo lo que devuelve el proveedor— y
     * contesta antes de que ninguna red haya terminado; medido en producción,
     * un reel de 13 MB tardó 85 segundos en salir en las tres redes mientras
     * que la subida contestó a los 20.
     *
     * <p>Devuelve {@code status} ("completed" cuando ya no falta ninguna),
     * {@code completed}/{@code total}, y {@code results}: una LISTA con una
     * fila por red, no un mapa indexado por red.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> status(String requestId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/uploadposts/status").queryParam("request_id", requestId).build())
                .retrieve()
                .body(Map.class);
    }

    /**
     * Las últimas subidas de la cuenta, en {@code history}.
     *
     * <p>Es el plan B de {@link #status(String)}: sirve cuando no se guardó el
     * identificador del envío. Sus filas traen los mismos campos que las de
     * status —{@code platform}, {@code success}, {@code platform_post_id},
     * {@code post_url}, {@code error_message}— así que las lee el mismo código.
     *
     * <p>Trae solo las diez últimas de TODA la llave, que es de todos los
     * negocios, y no admite filtro ni paginado por perfil (se probaron
     * {@code profile}, {@code profile_username} y {@code limit}: los dos
     * primeros se ignoran y el tercero da 400). Por eso solo vale recién
     * mandado el envío, cuando lo nuestro todavía está arriba.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> historial() {
        return restClient.get()
                .uri("/uploadposts/history")
                .retrieve()
                .body(Map.class);
    }

    /**
     * Los campos de texto de una publicación de fotos, sin las fotos.
     *
     * <p>Separado de {@link #publishPhotos} para poderlo probar sin red: fue
     * justo aquí donde Facebook enseñó otro texto del que la persona aprobó.
     *
     * <p><b>En {@code /upload_photos} Facebook no tiene campo propio de
     * texto.</b> Según el OpenAPI del proveedor, el texto visible de una foto
     * en Facebook sale del {@code description} GENERAL; {@code facebook_title}
     * existe pero no es lo que se ve, y {@code facebook_description} solo
     * existe para video. Mandar el texto de Facebook en su {@code _title} y el
     * texto común en {@code description} publicaba en Facebook el texto de
     * OTRA red —el de la primera elegida, que es el que viaja como común—.
     *
     * <p>Por eso, cuando Facebook trae texto propio, ese texto es el que va en
     * {@code description}. Como ese mismo campo lo leen también TikTok (fotos)
     * y LinkedIn cuando no reciben el suyo, a esas dos se les manda siempre su
     * {@code _description} explícito: el propio si lo hay, el común si no. Así
     * el texto de Facebook no se cuela en ninguna otra red.
     */
    MultiValueMap<String, Object> cuerpoFotos(String user, List<String> platforms, String caption,
            Map<String, String> captionsPorRed, PostFormat formato) {
        MultiValueMap<String, Object> body = baseFields(user, platforms);
        body.add("title", caption);

        String deFacebook = textoPropio(captionsPorRed, "facebook");
        body.add("description", deFacebook != null ? deFacebook : caption);

        textosPorRed(body, captionsPorRed, false);

        for (String red : List.of("tiktok", "linkedin")) {
            if (platforms.contains(red) && textoPropio(captionsPorRed, red) == null) {
                body.add(red + "_description", caption);
            }
        }

        formatoPorRed(body, platforms, formato);
        return body;
    }

    /** El texto propio de una red, o {@code null} si no trae o viene vacío. */
    private static String textoPropio(Map<String, String> captionsPorRed, String red) {
        if (captionsPorRed == null) {
            return null;
        }
        for (Map.Entry<String, String> e : captionsPorRed.entrySet()) {
            if (red.equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Añade el texto propio de cada red, cuando lo hay.
     *
     * <p>upload-post acepta {@code instagram_title}, {@code facebook_title},
     * {@code tiktok_title}, {@code linkedin_title} y {@code x_title}, y cada
     * uno pisa al {@code title} general. Es lo que permite mandar un texto
     * distinto por red SIN partir la publicacion en cinco envios: sigue siendo
     * una sola llamada, y el carrusel sigue siendo una sola publicacion.
     *
     * <p>Ademas del {@code *_title} se manda el {@code *_description} de las
     * redes que lo tienen, con el mismo texto. Segun el OpenAPI del proveedor,
     * en LinkedIn el cuerpo visible de la publicacion es el "commentary"
     * ({@code linkedin_description}), no el title; TikTok lo tiene para fotos y
     * Facebook y YouTube para video. Sin estos campos esas redes enseñaban el
     * texto comun —que era lo dictado— y no el que la persona aprobo por red.
     *
     * <p>Lo que no venga se queda sin su campo, y esa red usa el general. Es
     * el comportamiento que habia antes de todo esto.
     */
    private void textosPorRed(MultiValueMap<String, Object> body, Map<String, String> captionsPorRed, boolean video) {
        if (captionsPorRed == null || captionsPorRed.isEmpty()) {
            return;
        }
        captionsPorRed.forEach((platform, texto) -> {
            if (texto == null || texto.isBlank()) {
                return;
            }
            String red = platform.toLowerCase();
            body.add(red + "_title", texto);

            boolean conDescripcion = switch (red) {
                case "linkedin" -> true;
                case "tiktok" -> !video;
                case "facebook", "youtube" -> video;
                default -> false;
            };
            if (conDescripcion) {
                body.add(red + "_description", texto);
            }
        });
    }

    /**
     * Le dice al proveedor qué clase de publicación es, cuando hay algo que
     * decirle.
     *
     * <p><b>Solo para historias.</b> upload-post trata un video como reel
     * cuando no se le manda nada, que es justo lo que queremos para
     * {@code REEL} — y no mandarlo deja intacto el camino que hoy funciona—.
     * Una historia, en cambio, es invisible sin esto: se publicaría como reel,
     * que era el comportamiento de toda la aplicación hasta ahora, porque el
     * formato no existía y este campo no se mandaba nunca.
     *
     * <p>Ojo con los nombres, que no son simétricos: Facebook lo lee en
     * {@code facebook_media_type} y <b>Instagram en {@code media_type} a
     * secas</b>, no en {@code instagram_media_type}. Sale de la especificación
     * OpenAPI del proveedor, y es de las cosas que no dan error: un campo con
     * el nombre equivocado se ignora y la historia sale de reel sin que nadie
     * se entere.
     *
     * <p>Las demás redes no tienen historias —TikTok y YouTube no las publican
     * por API, LinkedIn no las tiene—, así que no hay más casos que atender.
     * Si algún día el proveedor rechaza el valor, este es el único sitio donde
     * tocarlo.
     */
    private void formatoPorRed(MultiValueMap<String, Object> body,
            List<String> platforms, PostFormat formato) {

        if (formato != PostFormat.STORY) {
            return;
        }
        if (platforms.contains("facebook")) {
            body.add("facebook_media_type", "STORIES");
        }
        if (platforms.contains("instagram")) {
            body.add("media_type", "STORIES");
        }
    }

    private MultiValueMap<String, Object> baseFields(String user, List<String> platforms) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("user", user);
        platforms.forEach(p -> body.add("platform[]", p));
        return body;
    }

    private ByteArrayResource download(String url) {
        try {
            HttpResponse<byte[]> response = downloader.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMinutes(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            return new ByteArrayResource(response.body()) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("No se pudo descargar el archivo para publicarlo: " + url, ex);
        }
    }
}

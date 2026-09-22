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
     *
     * @param titulo         el título común a todas las redes (ya recortado a 90)
     * @param captionsPorRed el caption de cada red, con la llave en mayúsculas
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> publishPhotos(
            String user, List<String> platforms, String titulo,
            Map<String, String> captionsPorRed, List<String> photoUrls, PostFormat formato) {
        MultiValueMap<String, Object> body = cuerpoFotos(user, platforms, titulo, captionsPorRed, formato);
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
    public Map<String, Object> publishVideo(String user, List<String> platforms, String titulo,
            Map<String, String> captionsPorRed, String videoUrl, PostFormat formato) {
        MultiValueMap<String, Object> body = cuerpoVideo(user, platforms, titulo, captionsPorRed, formato);
        body.add("video", download(videoUrl));

        return restClient.post()
                .uri("/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Cómo acabó un envío que el proveedor aceptó: por red, éxito o error.
     *
     * <p>Devuelve el JSON crudo de {@code /uploadposts/status}. Lo que trae —una
     * lista {@code results} con un objeto por red con {@code platform},
     * {@code success}, {@code platform_post_id} y {@code error}— lo interpreta
     * quien lo pide, con la misma tolerancia que el resto de respuestas.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> status(String requestId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/uploadposts/status")
                        .queryParam("request_id", requestId)
                        .build())
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> historial() {
        return restClient.get()
                .uri("/uploadposts/history")
                .retrieve()
                .body(Map.class);
    }

    /** Los campos de texto de una publicación de fotos, sin las fotos. Separado para poderlo probar sin red. */
    MultiValueMap<String, Object> cuerpoFotos(String user, List<String> platforms, String titulo,
            Map<String, String> captionsPorRed, PostFormat formato) {
        MultiValueMap<String, Object> body = baseFields(user, platforms);
        textosPorRed(body, platforms, titulo, captionsPorRed, false);
        formatoPorRed(body, platforms, formato);
        return body;
    }

    /** Los campos de texto de un video, sin el archivo. Separado para poderlo probar sin red. */
    MultiValueMap<String, Object> cuerpoVideo(String user, List<String> platforms, String titulo,
            Map<String, String> captionsPorRed, PostFormat formato) {
        MultiValueMap<String, Object> body = baseFields(user, platforms);
        textosPorRed(body, platforms, titulo, captionsPorRed, true);
        formatoPorRed(body, platforms, formato);
        return body;
    }

    /**
     * El título y el caption de cada red, cada uno en el campo que esa red lee.
     *
     * <p>Viajan DOS textos por publicación (22 sep 2026): un título corto,
     * común a todas las redes, y el caption de cada red. Antes viajaba uno
     * solo, en los campos de título, recortado a la red más estrecha; en
     * Facebook salía la primera frase partida con "…" y encima de otra red.
     *
     * <p>Qué campo muestra cada red, según el OpenAPI del proveedor
     * (https://docs.upload-post.com/openapi.json):
     * <ul>
     * <li><b>Instagram</b>: {@code instagram_title} es el pie. Va el caption.</li>
     * <li><b>Facebook fotos</b>: el texto visible es el {@code description}
     * GENERAL; no hay {@code facebook_description} para fotos y
     * {@code facebook_title} no se ve. Va el caption en {@code description}.</li>
     * <li><b>Facebook video</b>: {@code facebook_description} es el texto;
     * {@code facebook_title} es el nombre del video. Título y caption aparte.</li>
     * <li><b>TikTok fotos</b>: {@code tiktok_title} (90) y
     * {@code tiktok_description} se muestran los dos. Título y caption aparte.</li>
     * <li><b>TikTok video</b>: {@code tiktok_title} es el único texto y se ve
     * entero (2200). Va el caption.</li>
     * <li><b>LinkedIn</b>: {@code linkedin_description} es el comentario
     * visible; {@code linkedin_title} nombra el adjunto. Aparte.</li>
     * <li><b>YouTube</b>: título y descripción, los dos visibles. Aparte.</li>
     * </ul>
     *
     * <p>El {@code description} general lo leen también TikTok (fotos) y
     * LinkedIn cuando no reciben el suyo; por eso a toda red del envío se le
     * manda su campo explícito, con su caption o con el primero que haya, y
     * el de Facebook no se cuela en ninguna otra.
     */
    private void textosPorRed(MultiValueMap<String, Object> body, List<String> platforms,
            String titulo, Map<String, String> captionsPorRed, boolean video) {
        String primerCaption = captionsPorRed == null ? null : captionsPorRed.values().stream()
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(null);
        String tituloEfectivo = titulo != null && !titulo.isBlank() ? titulo : primerCaption;

        body.add("title", tituloEfectivo);

        String deFacebook = textoPropio(captionsPorRed, "facebook");
        String descripcion = deFacebook != null ? deFacebook : primerCaption != null ? primerCaption : tituloEfectivo;
        body.add("description", descripcion);

        for (String red : platforms) {
            String propio = textoPropio(captionsPorRed, red);
            String caption = propio != null ? propio : primerCaption != null ? primerCaption : tituloEfectivo;
            if (caption == null) {
                continue;
            }
            switch (red.toLowerCase()) {
                case "instagram" -> body.add("instagram_title", caption);
                case "facebook" -> {
                    body.add("facebook_title", tituloEfectivo);
                    if (video) {
                        body.add("facebook_description", caption);
                    }
                    // En fotos el caption ya va en {@code description} (arriba).
                }
                case "tiktok" -> {
                    if (video) {
                        body.add("tiktok_title", caption);
                    } else {
                        body.add("tiktok_title", tituloEfectivo);
                        body.add("tiktok_description", caption);
                    }
                }
                case "linkedin" -> {
                    body.add("linkedin_title", tituloEfectivo);
                    body.add("linkedin_description", caption);
                }
                case "youtube" -> {
                    // YouTube rechaza "<" y ">" en titulo y descripcion, y
                    // exige titulo (<= 100; el nuestro es <= 90). El titulo ya
                    // viene limpio de EspecTexto.recortarTitulo; la descripcion
                    // se limpia aqui porque es el caption de la red tal cual.
                    body.add("youtube_title", tituloEfectivo.replaceAll("[<>]", ""));
                    body.add("youtube_description", caption.replaceAll("[<>]", ""));
                }
                default -> body.add(red.toLowerCase() + "_title", caption);
            }
        }
    }

    /** El caption propio de una red, o {@code null} si no trae o viene vacío. */
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

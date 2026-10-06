package com.metricol.api.service.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Si lo que el negocio subió como logo es de verdad un logotipo, o la foto de
 * una persona (el retrato del dueño, una foto de perfil). Una foto no se pega
 * como sello en las publicaciones ni da los colores de la marca: el 6 oct 2026
 * un diseño salió con el retrato de Juan en la esquina, como si fuera su logo.
 *
 * <p>Se revisa una vez por archivo (se guarda qué URL se revisó) y, si es un
 * logotipo, se guardan sus colores para Marca, los diseños y los acabados.
 */
@Component
public class RevisorDeLogo {

    private static final Logger log = LoggerFactory.getLogger(RevisorDeLogo.class);

    static final String SISTEMA = """
            Te muestran la imagen que un negocio subio como su logo. Dices que es.
            Contestas SOLO un JSON: {"tipo": "LOGO" | "FOTO_PERSONA" | "FOTO" | "OTRO"}
            - LOGO: un logotipo, isotipo o marca grafica (letras, simbolo, emblema),
              aunque tenga fondo.
            - FOTO_PERSONA: una fotografia de una o mas personas (retrato, foto de
              perfil, selfie).
            - FOTO: otra fotografia (un local, un producto, un paisaje).
            - OTRO: cualquier otra cosa (una captura, un flyer completo).
            """;

    private final OpenAiClient client;
    private final WorkspaceRepository workspaces;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RevisorDeLogo(OpenAiClient client, WorkspaceRepository workspaces) {
        this.client = client;
        this.workspaces = workspaces;
    }

    /**
     * El logo que se puede pegar como sello, o {@code null} si no hay o si es
     * una foto. Revisa la primera vez que ve ese archivo; si la revisión no
     * se puede hacer, se usa como hasta ahora (como logo).
     */
    public String usable(Workspace w) {
        if (w == null || w.getLogoUrl() == null || w.getLogoUrl().isBlank()) {
            return null;
        }
        if (!w.getLogoUrl().equals(w.getLogoRevisado())) {
            revisar(w);
        }
        return Boolean.TRUE.equals(w.getLogoEsFoto()) ? null : w.getLogoUrl();
    }

    /** Revisa el logo actual y guarda qué es y sus colores. Nunca lanza. */
    public void revisar(Workspace w) {
        String url = w.getLogoUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            String tipo = tipo(client.describeImages(AiOperacion.REVISAR_LOGO, SISTEMA,
                    "¿Que es esta imagen?", List.of(url)));
            if (tipo == null) {
                return;
            }
            boolean esFoto = !"LOGO".equals(tipo);
            w.setLogoEsFoto(esFoto);
            w.setLogoRevisado(url);
            w.setMarcaColores(esFoto ? null : colores(url));
            workspaces.save(w);
            log.info("Logo de {} revisado: {}", w.getId(), tipo);
        } catch (Exception ex) {
            log.warn("No se pudo revisar el logo de {}: {}", w.getId(), ex.toString());
        }
    }

    String tipo(String respuesta) throws Exception {
        int inicio = respuesta == null ? -1 : respuesta.indexOf('{');
        int fin = respuesta == null ? -1 : respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            return null;
        }
        JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1));
        String tipo = n.path("tipo").asText("").strip().toUpperCase(java.util.Locale.ROOT);
        return List.of("LOGO", "FOTO_PERSONA", "FOTO", "OTRO").contains(tipo) ? tipo : null;
    }

    /** Los colores dominantes del logotipo ("#1A3A6B,#2BA84A"), o nulo si no se pudo bajar. */
    private String colores(String url) {
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200 || r.body().length == 0) {
                return null;
            }
            List<String> paleta = com.metricol.api.service.campaign.PaletaPublica.dominantes(r.body(), 4);
            return paleta.isEmpty() ? null : String.join(",", paleta);
        } catch (Exception ex) {
            return null;
        }
    }
}

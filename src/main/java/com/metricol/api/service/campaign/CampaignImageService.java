package com.metricol.api.service.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.CampaignImageResponse;
import com.metricol.api.entity.Post;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.service.ai.AiQuotaGuard;
import com.metricol.api.service.ai.AiUsageRecorder;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.ai.OpenAiImageClient;
import com.metricol.api.service.ai.OpenAiImageClient.Referencia;
import com.metricol.api.service.ai.OpenAiImageClient.Resultado;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

import jakarta.annotation.PreDestroy;

/**
 * Genera las imágenes de una campaña: las pide a gpt-image, las recorta a la
 * proporción de la red, las guarda en R2 como parte de Contenido y escribe el
 * texto que las acompaña.
 *
 * <p><b>Qué protege.</b>
 *
 * <ul>
 * <li>Las fotos de referencia y el logo solo pueden ser archivos del propio
 * workspace: se buscan por URL en su Contenido y no se baja nada que la app
 * mande por su cuenta.
 * <li>El tope diario de imágenes se comprueba con TODAS las piezas de la
 * campaña antes de pagar la primera.
 * <li>Nada se guarda hasta que todas las piezas llegaron: un carrusel a medias
 * no le sirve a nadie y ocuparía espacio.
 * </ul>
 *
 * <p><b>Las piezas de un carrusel se piden a la vez.</b> Una sola pieza tarda
 * hasta un minuto y Cloudflare corta la petición a los 100 segundos: cinco en
 * fila nunca cabrían. El orden se conserva porque se recogen por posición.
 * Esos hilos solo hablan con OpenAI; el gasto y el guardado ocurren después,
 * en el hilo de la petición, que es el que sabe de qué workspace se trata.
 */
@Service
public class CampaignImageService {

    private static final Logger log = LoggerFactory.getLogger(CampaignImageService.class);

    private static final int MAX_REFERENCIAS_POR_PIEZA = 4;
    private static final long MAX_BYTES_REFERENCIA = 20L * 1024 * 1024;
    /** Lo que suele pesar una pieza; solo sirve para comprobar el espacio antes de pagarla. */
    private static final long BYTES_ESTIMADOS_POR_PIEZA = 1_500_000L;
    private static final Set<String> TIPOS_ACEPTADOS = Set.of("image/png", "image/jpeg", "image/webp");

    private final OpenAiImageClient imagenes;
    private final OpenAiClient texto;
    private final R2StorageService storage;
    private final MediaAssetRepository assets;
    private final PostRepository posts;
    private final StorageQuotaService cuota;
    private final AiQuotaGuard cupo;
    private final AiUsageRecorder usos;
    private final ObjectMapper json = new ObjectMapper();

    private final ExecutorService pool = Executors.newFixedThreadPool(5, tarea -> {
        Thread hilo = new Thread(tarea, "campaign-image");
        hilo.setDaemon(true);
        return hilo;
    });

    public CampaignImageService(OpenAiImageClient imagenes, OpenAiClient texto, R2StorageService storage,
            MediaAssetRepository assets, PostRepository posts, StorageQuotaService cuota, AiQuotaGuard cupo,
            AiUsageRecorder usos) {
        this.imagenes = imagenes;
        this.texto = texto;
        this.storage = storage;
        this.assets = assets;
        this.posts = posts;
        this.cuota = cuota;
        this.cupo = cupo;
        this.usos = usos;
    }

    @PreDestroy
    void cerrar() {
        pool.shutdownNow();
    }

    /** Las proporciones y el nombre de cada formato de la app. */
    enum Formato {
        POST(4, 5, false), CAROUSEL(4, 5, true), STORY(9, 16, false);

        final int ratioAncho;
        final int ratioAlto;
        final boolean secuencia;

        Formato(int ratioAncho, int ratioAlto, boolean secuencia) {
            this.ratioAncho = ratioAncho;
            this.ratioAlto = ratioAlto;
            this.secuencia = secuencia;
        }

        static Formato de(String codigo) {
            String limpio = codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT);
            return switch (limpio) {
                case "POST" -> POST;
                case "CAROUSEL" -> CAROUSEL;
                case "STORY", "HISTORIA" -> STORY;
                default -> throw new IllegalArgumentException("Ese formato de imagen no existe.");
            };
        }
    }

    public CampaignImageResponse generar(User usuario, CampaignImageRequest peticion) {
        Formato formato = Formato.de(peticion.format() == null ? null : peticion.format().code());
        String logoUrl = peticion.brand() == null || peticion.brand().logoUrl() == null
                || peticion.brand().logoUrl().isBlank() ? null : peticion.brand().logoUrl().trim();
        List<String> recursos = fotosDe(formato, peticion.resourceUrls(), logoUrl);

        int piezas = 1;
        if (formato.secuencia) {
            if (recursos.size() < 2 || recursos.size() > 5) {
                throw new IllegalArgumentException(
                        "Un carrusel necesita entre 2 y 5 fotos. Tu logo no cuenta: se agrega solo.");
            }
            piezas = recursos.size();
        }

        Workspace workspace = usuario.getWorkspace();
        if (workspace == null) {
            throw new IllegalStateException("Elige un espacio de trabajo para crear la campaña.");
        }

        // Todo lo que puede fallar sin haber gastado nada, primero.
        if (!imagenes.disponible()) {
            throw new IllegalStateException("La generación de imágenes no está disponible por ahora.");
        }
        cupo.exigirCupo();
        int restantes = cupo.exigirCupoImagenes(piezas);
        cuota.verificar(BYTES_ESTIMADOS_POR_PIEZA * piezas, "campana.jpg");

        Map<String, Referencia> fotos = cargarReferencias(recursos);
        SelloDeLogo.Posicion posicionLogo = posicionDelLogo(peticion, formato);
        Referencia logo = posicionLogo == null ? null : cargarLogo(logoUrl);
        // Sin logo que pegar no hay espacio que reservar.
        SelloDeLogo.Posicion reservada = logo == null ? null : posicionLogo;

        String tamano = peticion.format().outputSize();
        List<String> prompts = new ArrayList<>();
        List<CompletableFuture<Resultado>> futuros = new ArrayList<>();
        for (int i = 0; i < piezas; i++) {
            List<Referencia> referencias = referenciasDePieza(formato, recursos, fotos, i);
            String prompt = armarPrompt(workspace, peticion, formato, i, piezas, referencias.size(), reservada);
            prompts.add(prompt);
            futuros.add(CompletableFuture.supplyAsync(() -> referencias.isEmpty()
                    ? imagenes.generar(prompt, tamano)
                    : imagenes.editar(prompt, referencias, tamano), pool));
        }

        List<Resultado> resultados = recogerEnOrden(futuros);

        // Con todas las piezas ya pagadas, se recortan y se suben. Si algo
        // falla a partir de aquí no se conserva nada a medias.
        List<String> claves = new ArrayList<>();
        List<MediaAsset> nuevos = new ArrayList<>();
        try {
            for (int i = 0; i < resultados.size(); i++) {
                byte[] jpeg = RecorteDeImagen.recortar(
                        resultados.get(i).imagen(), formato.ratioAncho, formato.ratioAlto, 0.9f);
                if (logo != null) {
                    jpeg = ponerLogo(jpeg, logo, reservada, formato == Formato.STORY);
                }
                String nombre = "campana-v" + Math.max(1, versionDe(peticion)) + "-" + (i + 1) + ".jpg";
                String clave = storage.claveNueva(workspace.getId(), nombre, "image/jpeg");
                String url = storage.subirBytes(clave, jpeg, "image/jpeg");
                claves.add(clave);
                nuevos.add(MediaAsset.builder()
                        .fileName(nombre)
                        .storageKey(clave)
                        .url(url)
                        .type(MediaType.IMAGE)
                        .sizeBytes((long) jpeg.length)
                        .contentType("image/jpeg")
                        .status(MediaAssetStatus.READY)
                        .build());
            }
            nuevos = assets.saveAll(nuevos);
        } catch (RuntimeException ex) {
            for (String clave : claves) {
                try {
                    storage.deleteByKey(clave);
                } catch (RuntimeException limpieza) {
                    log.warn("No se pudo retirar la imagen a medias {}: {}", clave, limpieza.getMessage());
                }
            }
            throw ex;
        }

        Textos textos = escribirTextos(workspace, peticion);
        List<String> urls = nuevos.stream().map(MediaAsset::getUrl).toList();
        return new CampaignImageResponse(
                nuevos.get(0).getId().toString(),
                versionDe(peticion),
                "PRODUCT",
                textos.titular(),
                textos.apoyo(),
                urls.get(0),
                urls,
                textos.caption(),
                prompts.get(0),
                piezas,
                restantes == Integer.MAX_VALUE ? null : restantes);
    }

    private static int versionDe(CampaignImageRequest peticion) {
        return peticion.version() == null ? 1 : peticion.version();
    }

    /**
     * Espera a todas las piezas, aunque una ya haya fallado: las que sí
     * llegaron se pagaron y tienen que quedar anotadas en el gasto.
     */
    private List<Resultado> recogerEnOrden(List<CompletableFuture<Resultado>> futuros) {
        List<Resultado> resultados = new ArrayList<>();
        RuntimeException primerFallo = null;

        for (CompletableFuture<Resultado> futuro : futuros) {
            try {
                Resultado resultado = futuro.get(130, TimeUnit.SECONDS);
                anotarGasto(resultado);
                resultados.add(resultado);
            } catch (ExecutionException ex) {
                if (primerFallo == null) {
                    primerFallo = ex.getCause() instanceof RuntimeException causa
                            ? causa
                            : new IllegalStateException("No se pudo generar la imagen. Inténtalo de nuevo.", ex);
                }
            } catch (TimeoutException ex) {
                futuro.cancel(true);
                if (primerFallo == null) {
                    primerFallo = new IllegalStateException("La IA tardó demasiado en responder. Inténtalo de nuevo.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Se interrumpió la generación de la imagen.", ex);
            }
        }

        if (primerFallo != null) {
            throw primerFallo;
        }
        return resultados;
    }

    private void anotarGasto(Resultado resultado) {
        try {
            usos.registrarImagen(resultado.modelo(), resultado.tokensEntrada(), resultado.tokensSalida());
        } catch (Exception ex) {
            // Perder una fila del reporte es mucho menos grave que tirar una
            // imagen que ya se pagó.
            log.warn("No se pudo anotar el gasto de la imagen: {}", ex.toString());
        }
    }

    // ------------------------------------------------------------ referencias

    /** Las fotos de referencia por URL, todas del Contenido de este workspace. */
    private Map<String, Referencia> cargarReferencias(List<String> urls) {
        Map<String, Referencia> fotos = new LinkedHashMap<>();
        if (urls.isEmpty()) {
            return fotos;
        }

        List<String> distintas = urls.stream().distinct().toList();
        Map<String, MediaAsset> delWorkspace = new LinkedHashMap<>();
        for (MediaAsset asset : assets.findByUrlIn(distintas)) {
            delWorkspace.put(asset.getUrl(), asset);
        }

        for (String url : distintas) {
            MediaAsset asset = delWorkspace.get(url);
            // El mismo mensaje para "no existe" y "es de otro": distinguirlos
            // le diría a quien pregunta qué archivos hay en otros espacios.
            if (asset == null || asset.getType() != MediaType.IMAGE
                    || asset.getStatus() != MediaAssetStatus.READY) {
                throw new IllegalArgumentException(
                        "Una de las fotos ya no está disponible en tu Contenido. Elígela de nuevo.");
            }
            fotos.put(url, descargar(asset));
        }
        return fotos;
    }

    /** El logo, si es de este workspace. Sin él la campaña sale igual: no se falla por decoración. */
    private Referencia cargarLogo(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        for (MediaAsset asset : assets.findByUrlIn(List.of(url))) {
            if (asset.getType() == MediaType.IMAGE && asset.getStatus() == MediaAssetStatus.READY) {
                try {
                    return descargar(asset);
                } catch (RuntimeException ex) {
                    log.warn("No se pudo usar el logo del workspace: {}", ex.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private Referencia descargar(MediaAsset asset) {
        String tipo = tipoAceptado(asset.getContentType());
        if (asset.getSizeBytes() != null && asset.getSizeBytes() > MAX_BYTES_REFERENCIA) {
            throw new IllegalArgumentException("Una de las fotos pesa demasiado para usarla como referencia.");
        }

        Path temporal = null;
        try {
            temporal = Files.createTempFile("picale-ref-", ".img");
            if (!storage.descargar(asset.getStorageKey(), temporal)) {
                throw new IllegalArgumentException(
                        "Una de las fotos ya no está disponible en tu Contenido. Elígela de nuevo.");
            }
            String extension = tipo.substring(tipo.indexOf('/') + 1).replace("jpeg", "jpg");
            return new Referencia(Files.readAllBytes(temporal), "referencia." + extension, tipo);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer una de las fotos. Inténtalo de nuevo.", ex);
        } finally {
            if (temporal != null) {
                try {
                    Files.deleteIfExists(temporal);
                } catch (IOException ignorada) {
                    // Un temporal que se queda no es motivo para fallar.
                }
            }
        }
    }

    private static String tipoAceptado(String contentType) {
        String tipo = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (tipo.equals("image/jpg")) {
            tipo = "image/jpeg";
        }
        if (!TIPOS_ACEPTADOS.contains(tipo)) {
            throw new IllegalArgumentException("Solo se pueden usar fotos JPG, PNG o WebP como referencia.");
        }
        return tipo;
    }

    /**
     * Qué fotos ve la IA en cada pieza: en un carrusel, la de su posición; en
     * las demás, todas las elegidas. El logo va al final de cada una.
     */
    private static List<Referencia> referenciasDePieza(Formato formato, List<String> urls,
            Map<String, Referencia> fotos, int indice) {
        List<Referencia> referencias = new ArrayList<>();
        if (formato.secuencia) {
            referencias.add(fotos.get(urls.get(indice)));
        } else {
            for (String url : urls.stream().distinct().limit(MAX_REFERENCIAS_POR_PIEZA).toList()) {
                referencias.add(fotos.get(url));
            }
        }
        return referencias;
    }

    /**
     * Las fotos que sí van a la IA: sin el logo (si vino entre ellas, se quita:
     * se pega solo) y, en una publicación o historia, sin repetidas y hasta
     * cuatro, que son contexto de UNA sola imagen. Un carrusel las conserva
     * todas y en orden: cada una es una diapositiva.
     */
    private static List<String> fotosDe(Formato formato, List<String> pedidas, String logoUrl) {
        List<String> sinLogo = (pedidas == null ? List.<String>of() : pedidas).stream()
                .filter(url -> url != null && !url.isBlank() && !url.trim().equals(logoUrl))
                .map(String::trim)
                .toList();
        return formato.secuencia ? sinLogo : sinLogo.stream().distinct().limit(MAX_REFERENCIAS_POR_PIEZA).toList();
    }

    /**
     * Dónde va el logo: lo que pidió la app, o el sitio de siempre para el
     * formato. {@code null} = sin logo.
     */
    private static SelloDeLogo.Posicion posicionDelLogo(CampaignImageRequest peticion, Formato formato) {
        String pedido = peticion.brand() == null ? null : peticion.brand().logoPosition();
        if (pedido != null && pedido.trim().equalsIgnoreCase("NONE")) {
            return null;
        }
        SelloDeLogo.Posicion elegida = SelloDeLogo.Posicion.de(pedido);
        if (elegida != null) {
            return elegida;
        }
        return formato == Formato.STORY ? SelloDeLogo.Posicion.TOP_CENTER : SelloDeLogo.Posicion.BOTTOM_CENTER;
    }

    /** Sin logo la imagen sirve igual: no se tira lo que ya se pagó por un logo que no se pudo leer. */
    private static byte[] ponerLogo(byte[] imagen, Referencia logo, SelloDeLogo.Posicion posicion, boolean historia) {
        try {
            return SelloDeLogo.poner(imagen, logo.bytes(), posicion, historia);
        } catch (RuntimeException ex) {
            log.warn("No se pudo pegar el logo: {}", ex.getMessage());
            return imagen;
        }
    }

    // ---------------------------------------------------------------- prompts

    static String armarPrompt(Workspace workspace, CampaignImageRequest p, Formato formato, int indice, int total,
            int fotos, SelloDeLogo.Posicion logo) {
        StringBuilder t = new StringBuilder();
        t.append("Create a polished, professional social media marketing image for a small business.\n");
        t.append("Business: ").append(valor(workspace.getName(), "a local business"));
        if (workspace.getGiro() != null && !workspace.getGiro().isBlank()) {
            t.append(" (").append(workspace.getGiro().trim()).append(")");
        }
        if (workspace.getCiudad() != null && !workspace.getCiudad().isBlank()) {
            t.append(", ").append(workspace.getCiudad().trim());
        }
        t.append(".\n");
        if (workspace.getDescripcion() != null && !workspace.getDescripcion().isBlank()) {
            t.append("About the business: ").append(workspace.getDescripcion().trim()).append("\n");
        }
        if (p.objective() != null && !p.objective().isBlank()) {
            t.append("Goal of the post: ").append(p.objective().trim()).append("\n");
        }
        t.append("What the image must communicate: ").append(p.brief().trim()).append("\n");
        if (p.visualStyle() != null && !p.visualStyle().isEmpty()) {
            t.append("Visual style: ").append(String.join(", ", p.visualStyle())).append("\n");
        }
        if (p.tone() != null && !p.tone().isBlank()) {
            t.append("Tone: ").append(p.tone().trim()).append("\n");
        }
        if (p.cta() != null && !p.cta().isBlank()) {
            t.append("Call to action, as short text inside the image: \"").append(p.cta().trim()).append("\"\n");
        }

        t.append("Composition: vertical layout. ").append(zonaSegura(formato)).append("\n");
        if (fotos > 0) {
            if (total > 1) {
                t.append("The reference photo shows the real subject of this slide: keep it recognizable and ")
                        .append("faithful, do not replace it with a different one.\n");
            } else if (fotos == 1) {
                t.append("The reference photo shows the real product or subject: keep the real people, equipment ")
                        .append("and place recognizable and faithful; do not replace them with different ones.\n");
            } else {
                t.append("The ").append(fotos).append(" reference photos are source material for ONE single image, ")
                        .append("not a carousel and not separate panels. Pick the strongest one as the main ")
                        .append("subject and use the others only as context, or combine at most two if it looks ")
                        .append("natural. Keep the real people, equipment and place recognizable and faithful; do ")
                        .append("not invent different ones.\n");
            }
        }
        if (logo != null) {
            t.append("Leave a clean, uncluttered area ").append(zonaLogo(logo))
                    .append(" — the business logo will be placed there afterwards. Put no text or key subject in it.\n");
        }
        if (total > 1) {
            t.append("This is slide ").append(indice + 1).append(" of ").append(total)
                    .append(" of a carousel: keep one consistent look across all slides.\n");
        }
        t.append("Never draw a logo, emblem or brand mark of any kind, and do not write the company's legal name ")
                .append("suffix (such as S.A. de C.V.): the real logo is added separately. ")
                .append("Any text must be in Spanish, LARGE, short and correctly spelled with proper accents ")
                .append("(at most a headline, the city and one call to action); avoid small print. ")
                .append("No watermarks and no fake interface elements.");
        return t.toString();
    }

    /**
     * Lo que se recorta de cada lado y lo que tapa la interfaz de la red, dicho
     * en porcentajes que la IA pueda respetar. gpt-image entrega 2:3: llegar a
     * 4:5 quita cerca del 8% de arriba y de abajo, y una historia quita cerca
     * del 8% de cada lado. Se pide un margen mayor porque la IA lo respeta a medias.
     */
    private static String zonaSegura(Formato formato) {
        return switch (formato) {
            case STORY -> "The image will be cropped to 9:16 and shown under the app's own interface: keep all "
                    + "text and every key subject inside the central 70% of the width, and nothing important "
                    + "in the top 14% or the bottom 20% of the height.";
            default -> "The image will be cropped to 4:5, removing the top and bottom edges: keep all text and "
                    + "every key subject inside the central 76% of the height (nothing in the top 12% or the "
                    + "bottom 12%), and never let text touch an edge.";
        };
    }

    private static String zonaLogo(SelloDeLogo.Posicion posicion) {
        String vertical = posicion.arriba() ? "at the top" : "at the bottom";
        String horizontal = posicion.izquierda() ? "left" : posicion.derecha() ? "right" : "center";
        return vertical + "-" + horizontal
                + " of the frame (about 40% of the width and 12% of the height, inside the safe zone)";
    }

    private static String valor(String texto, String porDefecto) {
        return texto == null || texto.isBlank() ? porDefecto : texto.trim();
    }

    // ------------------------------------------------------------------ textos

    record Textos(String titular, String apoyo, String caption) {
    }

    /**
     * El titular y el caption. Si la IA de texto falla, la campaña sale igual
     * con lo mínimo: la imagen ya se pagó y es lo que se pidió.
     */
    private Textos escribirTextos(Workspace workspace, CampaignImageRequest p) {
        String porDefecto = p.brief().trim();
        Textos respaldo = new Textos(
                porDefecto.length() <= 60 ? porDefecto : porDefecto.substring(0, 57) + "...", "", null);
        try {
            StringBuilder usuario = new StringBuilder();
            usuario.append("Negocio: ").append(valor(workspace.getName(), "un negocio local")).append("\n");
            if (workspace.getGiro() != null && !workspace.getGiro().isBlank()) {
                usuario.append("Giro: ").append(workspace.getGiro().trim()).append("\n");
            }
            if (workspace.getCiudad() != null && !workspace.getCiudad().isBlank()) {
                usuario.append("Ciudad: ").append(workspace.getCiudad().trim()).append("\n");
            }
            usuario.append("Lo que se quiere comunicar: ").append(porDefecto).append("\n");
            if (p.objective() != null && !p.objective().isBlank()) {
                usuario.append("Objetivo: ").append(p.objective().trim()).append("\n");
            }
            if (p.tone() != null && !p.tone().isBlank()) {
                usuario.append("Tono: ").append(p.tone().trim()).append("\n");
            }
            if (p.cta() != null && !p.cta().isBlank()) {
                usuario.append("Llamado a la acción: ").append(p.cta().trim()).append("\n");
            }

            List<String> anteriores = captionsAnteriores();
            if (!anteriores.isEmpty()) {
                usuario.append("\nCaptions que este negocio ya publicó (imita su voz y su forma de escribir, "
                        + "pero no repitas ninguno ni sus frases):\n");
                anteriores.forEach(c -> usuario.append("- ").append(c).append("\n"));
            }

            String crudo = texto.completeJson(AiOperacion.TEXTO_CAMPANA, """
                    Eres un community manager experto en redes sociales para negocios pequeños.
                    Escribes en español natural, sin sonar robótico. Devuelve SOLO un JSON con tres llaves:
                    "headline": titular de máximo 8 palabras, "supportingCopy": una frase de máximo 20 palabras
                    que lo complementa, y "caption": texto de 2 a 4 líneas para la publicación, con máximo 2
                    emojis, sin hashtags, que incluya el llamado a la acción si se dio uno.
                    """, usuario.toString());
            JsonNode nodo = json.readTree(crudo);
            String titular = limpio(nodo.path("headline").asText(null));
            String apoyo = limpio(nodo.path("supportingCopy").asText(null));
            String caption = limpio(nodo.path("caption").asText(null));
            return new Textos(titular == null ? respaldo.titular() : titular, apoyo == null ? "" : apoyo, caption);
        } catch (Exception ex) {
            log.warn("No se pudo escribir el texto de la campaña: {}", ex.toString());
            return respaldo;
        }
    }

    /**
     * Los últimos captions publicados del negocio, como contexto para escribir
     * el nuevo. Si no se pueden leer, la campaña sale igual: es una mejora del
     * texto, no una condición para crearlo.
     */
    private List<String> captionsAnteriores() {
        try {
            return posts.findTop8ByStatusAndArchivedAtIsNullOrderByPublishedAtDesc(PostStatus.PUBLISHED).stream()
                    .map(Post::getCaption)
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> c.trim().length() <= 300 ? c.trim() : c.trim().substring(0, 300) + "...")
                    .limit(5)
                    .toList();
        } catch (Exception ex) {
            log.warn("No se pudieron leer los captions anteriores: {}", ex.toString());
            return List.of();
        }
    }

    private static String limpio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}

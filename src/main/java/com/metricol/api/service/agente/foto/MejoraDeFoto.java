package com.metricol.api.service.agente.foto;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.AiUsageRepository;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.ai.AiUsageRecorder;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.ai.OpenAiImageClient;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.storage.R2StorageService;

/**
 * La foto real, mejorada como la entregaría un fotógrafo: con el encargo que
 * escribió el {@link DirectorDeFoto} para ESA foto, en el modelo de imágenes y
 * en calidad alta.
 *
 * <p><b>Sin perder el realismo.</b> El modelo de imágenes redibuja la foto
 * entera, así que puede inventar: una piedra de más, un letrero distinto. Por
 * eso la mejorada se compara con la original antes de usarla, con otra IA que
 * solo busca eso. Si cambió algo que no es luz, color o perspectiva, se tira
 * y la foto sigue por el retoque de siempre. Una foto falsa de un trabajo
 * real es peor que una foto sin mejorar.
 *
 * <p>Con tope diario por espacio ({@code openai.enhance-per-day}): cada mejora
 * cuesta centavos, pero un espacio que sube cien fotos no puede gastar sin
 * freno. La original nunca se toca; la mejorada es otro archivo.
 */
@Component
public class MejoraDeFoto {

    private static final Logger log = LoggerFactory.getLogger(MejoraDeFoto.class);

    private static final long MAX_BYTES = 25L * 1024 * 1024;

    /** Lado mayor de las copias que se mandan a comparar: basta para ver si algo cambió. */
    private static final int LADO_PARA_COMPARAR = 1024;

    static final String VERIFICAR = """
            You compare two images. Image 1 is a REAL photo taken by a business.
            Image 2 is the same photo after a professional retouch. A retouch may
            ONLY change light, exposure, contrast, white balance, color
            intensity, sharpness, noise, perspective straightening and a slight
            crop. It may also remove exactly the elements listed as
            ALLOWED REMOVALS, if any; anything else gone is not faithful.

            Answer ONLY a JSON object:
            {"fiel": true | false, "cambios": "if not faithful, what changed, in Spanish, one short sentence"}

            fiel is false if image 2:
            - adds, removes, moves, duplicates or replaces any object, person,
              plant, stone, tile, tool or structure;
            - changes the shape, proportions or layout of the subject;
            - changes the real color of a material (beyond correcting a color
              cast) or its texture into a different one;
            - alters, invents or garbles any text, number or signage;
            - changes a face or a body;
            - looks artificial: CGI, painterly, plastic, over-processed HDR.
            Otherwise fiel is true. Be strict about content, relaxed about light.
            """;

    private final R2StorageService storage;
    private final MediaAssetRepository assets;
    private final FfmpegImagen ffmpeg;
    private final OpenAiImageClient imagenes;
    private final OpenAiClient vision;
    private final OpenAiProperties props;
    private final AiUsageRecorder usos;
    private final AiUsageRepository registro;
    private final ObjectMapper mapper = new ObjectMapper();

    public MejoraDeFoto(R2StorageService storage, MediaAssetRepository assets, FfmpegImagen ffmpeg,
            OpenAiImageClient imagenes, OpenAiClient vision, OpenAiProperties props, AiUsageRecorder usos,
            AiUsageRepository registro) {
        this.storage = storage;
        this.assets = assets;
        this.ffmpeg = ffmpeg;
        this.imagenes = imagenes;
        this.vision = vision;
        this.props = props;
        this.usos = usos;
        this.registro = registro;
    }

    /** Cuántas mejoras le quedan hoy al espacio. */
    public int quedanHoy(UUID workspaceId) {
        if (!imagenes.disponible() || props.getEnhancePerDay() <= 0 || workspaceId == null) {
            return 0;
        }
        LocalDate hoy = LocalDate.now();
        long hechas = registro.countByWorkspaceIdAndOperacionAndCreatedAtBetween(workspaceId,
                AiOperacion.AGENTE_MEJORAR, hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay());
        return (int) Math.max(0, props.getEnhancePerDay() - hechas);
    }

    /** Lo que salió: la copia mejorada, o por qué no. */
    public record Mejorada(MediaAsset asset, String noSalio) {

        public boolean salio() {
            return asset != null;
        }
    }

    /** Nunca lanza: lo que no salga se dice en {@link Mejorada#noSalio}. */
    public Mejorada mejorar(MediaAsset foto, UUID workspaceId, DirectorDeFoto.Direccion direccion) {
        if (direccion == null || !direccion.mejorar() || foto.getStorageKey() == null
                || (foto.getSizeBytes() != null && foto.getSizeBytes() > MAX_BYTES)) {
            return new Mejorada(null, "La foto no se pudo preparar para mejorarla.");
        }
        if (quedanHoy(workspaceId) <= 0) {
            return new Mejorada(null, "Hoy ya no me quedan mejoras con IA.");
        }
        Path original = null;
        try {
            original = Files.createTempFile("picale-mejora-", ".img");
            if (!storage.descargar(foto.getStorageKey(), original)) {
                return new Mejorada(null, "No pude bajar la foto para mejorarla.");
            }
            // Pasada por ffmpeg: JPEG derecho (la orientación del teléfono aplicada) y sin metadatos.
            byte[] limpia = ffmpeg.sanear(original);
            BufferedImage antes = limpia == null ? null : ImageIO.read(new ByteArrayInputStream(limpia));
            if (antes == null) {
                return new Mejorada(null, "La foto no se pudo leer para mejorarla.");
            }

            OpenAiImageClient.Resultado r = imagenes.editar(prompt(direccion),
                    List.of(new OpenAiImageClient.Referencia(limpia, "foto.jpg", "image/jpeg")),
                    lienzo(antes.getWidth(), antes.getHeight()), props.getImageEnhanceQuality());
            try {
                usos.registrar(AiOperacion.AGENTE_MEJORAR, r.modelo(), r.tokensEntrada(), r.tokensSalida(),
                        props.getImagePricing());
            } catch (Exception ex) {
                log.warn("No se pudo anotar la mejora: {}", ex.toString());
            }
            BufferedImage generada = ImageIO.read(new ByteArrayInputStream(r.imagen()));
            if (generada == null) {
                return new Mejorada(null, "La mejora no salió.");
            }
            BufferedImage despues = alMismoFormato(generada, antes.getWidth(), antes.getHeight());

            String cambio = cambioInventado(antes, despues, direccion.quitar());
            if (cambio != null) {
                log.info("Mejora de {} descartada por no ser fiel: {}", foto.getId(), cambio);
                return new Mejorada(null, "La mejora cambiaba la foto (" + cambio + "); la dejé real.");
            }

            byte[] jpeg = aJpeg(despues, 0.93f);
            String nombre = "mejorada-" + foto.getFileName();
            String clave = storage.claveNueva(workspaceId, nombre.endsWith(".jpg") ? nombre : nombre + ".jpg",
                    "image/jpeg");
            String url = storage.subirBytes(clave, jpeg, "image/jpeg");
            MediaAsset guardada = assets.save(MediaAsset.builder()
                    .fileName(nombre)
                    .storageKey(clave)
                    .url(url)
                    .type(MediaType.IMAGE)
                    .contentType("image/jpeg")
                    .sizeBytes((long) jpeg.length)
                    .status(MediaAssetStatus.READY)
                    .generadaPorIa(true)
                    .build());
            return new Mejorada(guardada, null);
        } catch (Exception ex) {
            log.warn("No se pudo mejorar {}: {}", foto.getId(), ex.toString());
            return new Mejorada(null, "La mejora no salió.");
        } finally {
            if (original != null) {
                try {
                    Files.deleteIfExists(original);
                } catch (IOException ignorada) {
                    // Un temporal suelto no rompe nada.
                }
            }
        }
    }

    /** El encargo del director con las reglas que no se negocian. */
    static String prompt(DirectorDeFoto.Direccion d) {
        StringBuilder t = new StringBuilder();
        t.append("Retouch this REAL photograph so it looks professionally shot, while keeping it 100% real ")
                .append("and faithful to the scene.\n\n");
        t.append("EDITS FOR THIS PHOTO:\n").append(d.encargo().strip()).append("\n\n");
        if (!d.quitar().isEmpty()) {
            t.append("REMOVE (only these; rebuild the surface behind them so it looks untouched):\n");
            d.quitar().forEach(q -> t.append("- ").append(q).append('\n'));
            t.append('\n');
        }
        if (!d.conservar().isEmpty()) {
            t.append("MUST STAY IDENTICAL:\n");
            d.conservar().forEach(c -> t.append("- ").append(c).append('\n'));
            t.append('\n');
        }
        t.append("""
                ABSOLUTE RULES:
                - Same scene, same composition and camera position. Only perspective straightening and a slight crop are allowed.
                - Do not add, remove, move, duplicate or replace any object, person, plant, stone, tile, tool or structure, except removing exactly what is listed under REMOVE.
                - Keep the real colors and textures of every material; only correct color casts.
                - Keep every text, number and sign exactly as it is. Faces and bodies stay untouched.
                - Do not add text, logos, watermarks, borders or frames.
                - Natural daylight photographic look: no HDR halos, no oversaturation, no painterly, CGI or plastic look.
                The result must be indistinguishable from a real professional photo of the same place.
                """);
        return t.toString();
    }

    /** El lienzo de gpt-image más cercano a la proporción de la foto. */
    static String lienzo(int ancho, int alto) {
        double proporcion = (double) ancho / Math.max(1, alto);
        if (proporcion >= 1.2) {
            return "1536x1024";
        }
        if (proporcion <= 0.83) {
            return "1024x1536";
        }
        return "1024x1024";
    }

    /**
     * La generada recortada al centro a la proporción de la original: el
     * lienzo de la IA es 2:3, 1:1 o 3:2 y la foto, casi siempre 3:4 o 4:3. Se
     * publica con el mismo encuadre que la persona eligió.
     */
    static BufferedImage alMismoFormato(BufferedImage generada, int anchoOriginal, int altoOriginal) {
        double objetivo = (double) anchoOriginal / Math.max(1, altoOriginal);
        int w = generada.getWidth();
        int h = generada.getHeight();
        int recorteW = w;
        int recorteH = h;
        if ((double) w / h > objetivo) {
            recorteW = (int) Math.round(h * objetivo);
        } else {
            recorteH = (int) Math.round(w / objetivo);
        }
        int x = (w - recorteW) / 2;
        int y = (h - recorteH) / 2;
        BufferedImage salida = new BufferedImage(recorteW, recorteH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            g.drawImage(generada, -x, -y, null);
        } finally {
            g.dispose();
        }
        return salida;
    }

    /**
     * Qué inventó la mejora, o {@code null} si es fiel. Si no se puede
     * comparar, se descarta: sin la comparación no hay garantía de realismo.
     */
    String cambioInventado(BufferedImage antes, BufferedImage despues, List<String> permitidoQuitar) {
        try {
            String modelo = props.getDirectorModel();
            String respuesta = vision.mirar(AiOperacion.AGENTE_VERIFICAR_FOTO, modelo, "low", VERIFICAR,
                    "Image 1 is the original, image 2 the retouch. Is image 2 faithful?"
                            + (permitidoQuitar.isEmpty() ? ""
                                    : "\nALLOWED REMOVALS (these may be gone, nothing else): "
                                            + String.join("; ", permitidoQuitar)),
                    List.of(comoDato(antes), comoDato(despues)), "high", props.getDirectorPricing());
            int inicio = respuesta.indexOf('{');
            int fin = respuesta.lastIndexOf('}');
            if (inicio < 0 || fin <= inicio) {
                return "no se pudo comparar";
            }
            JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1));
            if (n.path("fiel").asBoolean(false)) {
                return null;
            }
            String cambios = n.path("cambios").asText("").strip();
            return cambios.isBlank() ? "cambió el contenido" : cambios.replaceAll("[.\\s]+$", "");
        } catch (Exception ex) {
            log.warn("No se pudo comparar la mejora: {}", ex.toString());
            return "no se pudo comparar";
        }
    }

    private static String comoDato(BufferedImage imagen) {
        double escala = Math.min(1.0, (double) LADO_PARA_COMPARAR / Math.max(imagen.getWidth(), imagen.getHeight()));
        int w = Math.max(1, (int) Math.round(imagen.getWidth() * escala));
        int h = Math.max(1, (int) Math.round(imagen.getHeight() * escala));
        BufferedImage chica = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = chica.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(imagen, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(aJpeg(chica, 0.85f));
    }

    static byte[] aJpeg(BufferedImage imagen, float calidad) {
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                MemoryCacheImageOutputStream flujo = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametros.setCompressionQuality(calidad);
            escritor.setOutput(flujo);
            escritor.write(null, new IIOImage(imagen, null, null), parametros);
            flujo.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar la foto mejorada.", ex);
        } finally {
            escritor.dispose();
        }
    }
}

package com.metricol.api.service.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.service.storage.R2StorageService;

/**
 * Una huella de 64 bits de cómo se ve una foto, para encontrar las repetidas.
 *
 * <p>Es un "dHash": la foto en gris a 9×8 píxeles, y cada bit dice si un
 * píxel es más claro que el de su derecha. Dos fotos casi iguales —la misma
 * toma dos veces, la misma con otro recorte o compresión— dan huellas que
 * difieren en pocos bits; dos fotos distintas, en muchos.
 *
 * <p>Sin IA ni red: se calcula en el servidor en milisegundos, así que el
 * agente puede descartar una repetida antes de gastar en revisarla. Lo que
 * Java no sabe leer (HEIC del iPhone, WebP) se lee con ffmpeg; un video se
 * compara por el cuadro de su mitad.
 */
@Component
public class HuellaDeImagen {

    private static final Logger log = LoggerFactory.getLogger(HuellaDeImagen.class);

    /** Hasta cuántos bits distintos se consideran la misma foto. */
    public static final int PARECIDAS = 6;

    /**
     * Hasta cuántos bits son de la misma ráfaga: tomas seguidas de lo mismo,
     * con otro gesto o un poco movidas. Solo se usa dentro de una misma tanda
     * de subida; entre fotos de días distintos sería confundir dos parecidas.
     */
    public static final int RAFAGA = 12;

    private static final long MAX_BYTES = 25L * 1024 * 1024;

    /** El lado con el que se mide la nitidez: suficiente para ver un borde, barato de calcular. */
    private static final int LADO_NITIDEZ = 512;

    private final R2StorageService storage;
    private final FfmpegImagen ffmpeg;

    public HuellaDeImagen(R2StorageService storage, FfmpegImagen ffmpeg) {
        this.storage = storage;
        this.ffmpeg = ffmpeg;
    }

    /** La huella de una foto de Contenido, o {@code null} si no se pudo leer. Nunca lanza. */
    public Long de(MediaAsset asset) {
        BufferedImage imagen = leer(asset);
        return imagen == null ? null : huella(imagen);
    }

    /**
     * La huella de un video: la del cuadro de su mitad. La mitad y no el
     * principio porque muchos negocios abren todos sus videos con la misma
     * entrada, y dos videos distintos con la misma entrada no son repetidos.
     * ffmpeg pide solo ese tramo a la URL, sin bajar el video entero.
     */
    public Long deVideo(MediaAsset video, double segundos) {
        try {
            byte[] cuadro = ffmpeg.cuadro(video.getUrl(), Math.max(0, segundos / 2), 128, 5);
            return cuadro == null ? null : de(cuadro);
        } catch (Exception ex) {
            log.debug("Sin huella para el video {}: {}", video.getId(), ex.toString());
            return null;
        }
    }

    /**
     * Qué tan nítida es una foto: la varianza del laplaciano (cuánto cambian
     * los bordes), en un lado de {@value #LADO_NITIDEZ}. Solo sirve para
     * comparar tomas de lo mismo —la movida da menos—, no como calificación.
     *
     * @return la nitidez, o {@code null} si no se pudo leer
     */
    public Double nitidez(MediaAsset asset) {
        BufferedImage imagen = leer(asset);
        return imagen == null ? null : nitidez(imagen);
    }

    /** La huella de unos bytes de imagen, o {@code null} si Java no sabe leerlos. */
    public static Long de(byte[] imagen) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imagen));
            return original == null ? null : huella(original);
        } catch (Exception ex) {
            return null;
        }
    }

    /** ¿Son la misma foto? */
    public static boolean parecidas(long a, long b) {
        return distancia(a, b) <= PARECIDAS;
    }

    /** Cuántos bits de diferencia hay entre dos huellas. */
    public static int distancia(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    static long huella(BufferedImage original) {
        BufferedImage chica = gris(original, 9, 8);
        long huella = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int izquierda = chica.getRaster().getSample(x, y, 0);
                int derecha = chica.getRaster().getSample(x + 1, y, 0);
                huella = (huella << 1) | (izquierda > derecha ? 1 : 0);
            }
        }
        return huella;
    }

    static double nitidez(BufferedImage original) {
        int ancho = original.getWidth();
        int alto = original.getHeight();
        double escala = Math.min(1.0, (double) LADO_NITIDEZ / Math.max(ancho, alto));
        int w = Math.max(3, (int) Math.round(ancho * escala));
        int h = Math.max(3, (int) Math.round(alto * escala));
        BufferedImage g = gris(original, w, h);
        var r = g.getRaster();
        double suma = 0;
        double sumaCuadrados = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int lap = r.getSample(x - 1, y, 0) + r.getSample(x + 1, y, 0) + r.getSample(x, y - 1, 0)
                        + r.getSample(x, y + 1, 0) - 4 * r.getSample(x, y, 0);
                suma += lap;
                sumaCuadrados += (double) lap * lap;
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        double media = suma / n;
        return sumaCuadrados / n - media * media;
    }

    private static BufferedImage gris(BufferedImage original, int ancho, int alto) {
        BufferedImage chica = new BufferedImage(ancho, alto, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = chica.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, ancho, alto, null);
        g.dispose();
        return chica;
    }

    /**
     * La foto, leída de R2. Lo que Java no sabe leer (HEIC, WebP) pasa por
     * ffmpeg a un JPEG de {@value #LADO_NITIDEZ} de ancho, que alcanza para la
     * huella y para la nitidez. Nunca lanza.
     */
    private BufferedImage leer(MediaAsset asset) {
        if (asset.getStorageKey() == null || (asset.getSizeBytes() != null && asset.getSizeBytes() > MAX_BYTES)) {
            return null;
        }
        Path temporal = null;
        try {
            temporal = Files.createTempFile("picale-huella-", ".img");
            if (!storage.descargar(asset.getStorageKey(), temporal)) {
                return null;
            }
            BufferedImage imagen = ImageIO.read(temporal.toFile());
            if (imagen != null) {
                return imagen;
            }
            byte[] convertida = ffmpeg.fotograma(temporal, "0", LADO_NITIDEZ, 3);
            return convertida == null ? null : ImageIO.read(new ByteArrayInputStream(convertida));
        } catch (Exception ex) {
            log.debug("No pude leer {}: {}", asset.getId(), ex.toString());
            return null;
        } finally {
            FfmpegImagen.borrar(temporal);
        }
    }
}

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
 * agente puede descartar una repetida antes de gastar en revisarla.
 */
@Component
public class HuellaDeImagen {

    private static final Logger log = LoggerFactory.getLogger(HuellaDeImagen.class);

    /** Hasta cuántos bits distintos se consideran la misma foto. */
    public static final int PARECIDAS = 6;

    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private final R2StorageService storage;

    public HuellaDeImagen(R2StorageService storage) {
        this.storage = storage;
    }

    /** La huella de un archivo de Contenido, o {@code null} si no se pudo leer. Nunca lanza. */
    public Long de(MediaAsset asset) {
        if (asset.getStorageKey() == null || (asset.getSizeBytes() != null && asset.getSizeBytes() > MAX_BYTES)) {
            return null;
        }
        Path temporal = null;
        try {
            temporal = Files.createTempFile("picale-huella-", ".img");
            if (!storage.descargar(asset.getStorageKey(), temporal)) {
                return null;
            }
            return de(Files.readAllBytes(temporal));
        } catch (Exception ex) {
            log.debug("Sin huella para {}: {}", asset.getId(), ex.toString());
            return null;
        } finally {
            FfmpegImagen.borrar(temporal);
        }
    }

    /** La huella de unos bytes de imagen, o {@code null} si Java no sabe leerlos (HEIC, WebP). */
    public static Long de(byte[] imagen) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imagen));
            if (original == null) {
                return null;
            }
            BufferedImage chica = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = chica.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, 9, 8, null);
            g.dispose();

            long huella = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int izquierda = chica.getRaster().getSample(x, y, 0);
                    int derecha = chica.getRaster().getSample(x + 1, y, 0);
                    huella = (huella << 1) | (izquierda > derecha ? 1 : 0);
                }
            }
            return huella;
        } catch (Exception ex) {
            return null;
        }
    }

    /** ¿Son la misma foto? */
    public static boolean parecidas(long a, long b) {
        return Long.bitCount(a ^ b) <= PARECIDAS;
    }
}

package com.metricol.api.service.campaign;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Recorta desde el centro lo que generó la IA a la proporción de publicación.
 *
 * <p>gpt-image entrega lienzos de 1024x1536 (2:3) y las redes piden 4:5 para el
 * feed y 9:16 para historias. Recortar y no encajar con bandas: una imagen de
 * campaña con franjas de relleno se ve como un error, y el prompt ya le pidió
 * a la IA dejar margen de seguridad alrededor de lo importante.
 *
 * <p>Sale siempre como JPEG: pesa una fracción del PNG que suele devolver la
 * IA, y estas imágenes cuentan contra el espacio del workspace.
 */
final class RecorteDeImagen {

    private RecorteDeImagen() {
    }

    /**
     * @param origen bytes de una imagen PNG, JPEG o GIF (lo que lee ImageIO)
     * @param ratioAncho lado horizontal de la proporción que se quiere (4 en 4:5)
     * @param ratioAlto lado vertical (5 en 4:5)
     * @param calidad de 0 a 1
     */
    static byte[] recortar(byte[] origen, int ratioAncho, int ratioAlto, float calidad) {
        return recortar(origen, ratioAncho, ratioAlto, calidad, 0.5);
    }

    /**
     * @param cortaArriba qué parte del recorte VERTICAL se toma de arriba: 0,5 es
     *        parejo; 0,8 quita casi todo de arriba (para anuncios con el texto abajo)
     */
    static byte[] recortar(byte[] origen, int ratioAncho, int ratioAlto, float calidad, double cortaArriba) {
        BufferedImage imagen;
        try {
            imagen = ImageIO.read(new ByteArrayInputStream(origen));
        } catch (IOException ex) {
            throw new IllegalStateException("La imagen generada no se pudo leer.", ex);
        }
        if (imagen == null) {
            throw new IllegalStateException("La imagen generada no tiene un formato conocido.");
        }

        int ancho = imagen.getWidth();
        int alto = imagen.getHeight();
        double objetivo = (double) ratioAncho / ratioAlto;

        int anchoFinal = ancho;
        int altoFinal = alto;
        if ((double) ancho / alto > objetivo) {
            anchoFinal = (int) Math.round(alto * objetivo);
        } else {
            altoFinal = (int) Math.round(ancho / objetivo);
        }
        int x = (ancho - anchoFinal) / 2;
        int y = (int) Math.round((alto - altoFinal) * Math.max(0, Math.min(1, cortaArriba)));

        // RGB y sobre blanco: JPEG no tiene canal alfa y un PNG con
        // transparencia se guardaría con el fondo en negro.
        BufferedImage salida = new BufferedImage(anchoFinal, altoFinal, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, anchoFinal, altoFinal);
            g.drawImage(imagen.getSubimage(x, y, anchoFinal, altoFinal), 0, 0, null);
        } finally {
            g.dispose();
        }
        return aJpeg(salida, calidad);
    }

    private static byte[] aJpeg(BufferedImage imagen, float calidad) {
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
            throw new IllegalStateException("No se pudo guardar la imagen generada.", ex);
        } finally {
            escritor.dispose();
        }
    }
}

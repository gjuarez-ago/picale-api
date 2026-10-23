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
 *
 * <p><b>Nunca se corta a través del contenido.</b> El prompt pide dejar libres las
 * franjas que se van a quitar, pero la IA no siempre obedece: el 22 oct 2026 un
 * titular gigante quedó a medias ("Pasteles" sin su parte de arriba). Ahora el corte
 * vertical se elige mirando la imagen —se quitan las franjas con menos detalle— y,
 * si arriba y abajo hay contenido, no se corta nada: la imagen entera se encaja y los
 * lados se rellenan con su propio fondo desenfocado. Un relleno se nota menos que un
 * texto cortado.
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
        if (altoFinal < alto) {
            int mejor = mejorCorteVertical(imagen, altoFinal, y);
            if (mejor < 0) {
                return aJpeg(encajar(imagen, objetivo), calidad);
            }
            y = mejor;
        }

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

    /**
     * Detalle medio a partir del cual una franja "tiene contenido" (letras, platillos,
     * personas). Un fondo liso o un degradado suave quedan muy por debajo.
     */
    static final double CON_CONTENIDO = 4.0;

    /**
     * El renglón donde empieza el recorte que quita menos contenido; entre los que
     * quitan lo mismo, el más cercano al preferido por la composición. {@code -1} si
     * cualquier recorte se llevaría contenido: entonces no se corta.
     */
    static int mejorCorteVertical(BufferedImage imagen, int altoFinal, int preferido) {
        int alto = imagen.getHeight();
        int sobra = alto - altoFinal;
        double[] acumulado = new double[alto + 1];
        double[] fila = detallePorFila(imagen);
        for (int y = 0; y < alto; y++) {
            acumulado[y + 1] = acumulado[y] + fila[y];
        }
        int mejor = -1;
        double menor = Double.MAX_VALUE;
        for (int y = 0; y <= sobra; y++) {
            double quitado = acumulado[y] + (acumulado[alto] - acumulado[y + altoFinal]);
            if (quitado < menor - 1e-9 || (Math.abs(quitado - menor) <= 1e-9
                    && Math.abs(y - preferido) < Math.abs(mejor - preferido))) {
                menor = quitado;
                mejor = y;
            }
        }
        return sobra > 0 && menor / sobra > CON_CONTENIDO ? -1 : mejor;
    }

    /**
     * Cuánto cambia la imagen en cada renglón: diferencia media de luz con el pixel de
     * arriba y con el de al lado (0 = liso). Las letras y los objetos dan mucho; un
     * fondo de color, casi nada.
     */
    static double[] detallePorFila(BufferedImage imagen) {
        int ancho = imagen.getWidth();
        int alto = imagen.getHeight();
        double[] detalle = new double[alto];
        int paso = Math.max(1, ancho / 256);
        int[] previa = luzDeFila(imagen, 0, paso);
        for (int y = 0; y < alto; y++) {
            int[] actual = y == 0 ? previa : luzDeFila(imagen, y, paso);
            double suma = 0;
            for (int i = 0; i < actual.length; i++) {
                suma += Math.abs(actual[i] - previa[i]);
                if (i > 0) suma += Math.abs(actual[i] - actual[i - 1]);
            }
            detalle[y] = suma / actual.length;
            previa = actual;
        }
        if (alto > 1) detalle[0] = detalle[1];
        return detalle;
    }

    private static int[] luzDeFila(BufferedImage imagen, int y, int paso) {
        int[] luz = new int[(imagen.getWidth() + paso - 1) / paso];
        for (int i = 0, x = 0; x < imagen.getWidth(); i++, x += paso) {
            int rgb = imagen.getRGB(x, y);
            luz[i] = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
        }
        return luz;
    }

    /**
     * La imagen entera dentro de la proporción pedida, sin cortar nada: centrada, con
     * los lados rellenos de la misma imagen ampliada y desenfocada (y un poco más
     * oscura, para que el borde no compita con el anuncio).
     */
    static BufferedImage encajar(BufferedImage imagen, double objetivo) {
        int alto = imagen.getHeight();
        int ancho = imagen.getWidth();
        int anchoFinal = Math.max(ancho, (int) Math.round(alto * objetivo));
        BufferedImage salida = new BufferedImage(anchoFinal, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            // Desenfoque barato y suficiente: reducir mucho y volver a ampliar suavizado.
            int chicoAncho = Math.max(1, anchoFinal / 24);
            int chicoAlto = Math.max(1, alto / 24);
            BufferedImage chico = new BufferedImage(chicoAncho, chicoAlto, BufferedImage.TYPE_INT_RGB);
            Graphics2D gc = chico.createGraphics();
            gc.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gc.drawImage(imagen, 0, 0, chicoAncho, chicoAlto, null);
            gc.dispose();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(chico, 0, 0, anchoFinal, alto, null);
            g.setColor(new Color(0, 0, 0, 40));
            g.fillRect(0, 0, anchoFinal, alto);
            g.drawImage(imagen, (anchoFinal - ancho) / 2, 0, null);
        } finally {
            g.dispose();
        }
        return salida;
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

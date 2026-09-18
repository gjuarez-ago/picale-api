package com.metricol.api.service.campaign;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Pega el logo REAL del negocio sobre la imagen que generó la IA.
 *
 * <p>La IA no reproduce logos: redibuja todo el lienzo, y el resultado es un
 * logo parecido pero inventado —otro icono, otras letras—. Aquí el archivo del
 * logo se coloca tal cual, así que sale idéntico y siempre completo.
 *
 * <p><b>Va sobre una placa</b> (un rectángulo redondeado) y no suelto. Casi
 * todos los logos vienen con fondo blanco o pensados para uno claro; pegados
 * directamente sobre una foto se ven como un parche. La placa es blanca, salvo
 * cuando el logo es claro (letras blancas sobre transparente): ahí sería
 * invisible y la placa se vuelve oscura.
 *
 * <p>Antes de escalarlo se le recortan los márgenes vacíos: un logo con mucho
 * aire alrededor, como el de una hoja blanca, salía diminuto.
 */
final class SelloDeLogo {

    enum Posicion {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

        /** El código de la app, o {@code null} si no es uno conocido. */
        static Posicion de(String codigo) {
            if (codigo == null) {
                return null;
            }
            try {
                return valueOf(codigo.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        boolean arriba() {
            return this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT;
        }

        boolean izquierda() {
            return this == TOP_LEFT || this == BOTTOM_LEFT;
        }

        boolean derecha() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }
    }

    /** Lo más ancho que puede ser el logo, sin contar la placa, respecto al ancho de la imagen. */
    private static final double ANCHO_MAX = 0.34;
    /** Y lo más alto, respecto al alto de la imagen. */
    private static final double ALTO_MAX = 0.09;
    private static final double MARGEN_X = 0.04;
    private static final double MARGEN_Y = 0.035;
    /**
     * En historias la app de la red pone su interfaz —el perfil arriba, la caja
     * de respuesta abajo—: se deja más aire, y más abajo que arriba.
     */
    private static final double MARGEN_Y_HISTORIA_ARRIBA = 0.14;
    private static final double MARGEN_Y_HISTORIA_ABAJO = 0.20;

    private SelloDeLogo() {
    }

    /**
     * @param imagen la imagen ya recortada (JPEG, PNG…)
     * @param logo el archivo del logo tal como está en Contenido
     * @param historia si el lienzo es una historia (9:16): margen vertical mayor
     * @return la imagen con el logo, en JPEG
     * @throws IllegalArgumentException si el logo no se puede leer (WebP, por ejemplo)
     */
    static byte[] poner(byte[] imagen, byte[] logo, Posicion posicion, boolean historia) {
        BufferedImage base = leer(imagen, "La imagen generada no se pudo leer.");
        BufferedImage original = leer(logo, "El logo no se puede leer: usa un archivo JPG o PNG.");

        Contenido contenido = recortarVacio(original);
        int ancho = base.getWidth();
        int alto = base.getHeight();

        double escala = Math.min(ANCHO_MAX * ancho / contenido.imagen().getWidth(),
                ALTO_MAX * alto / contenido.imagen().getHeight());
        int logoAncho = Math.max(1, (int) Math.round(contenido.imagen().getWidth() * escala));
        int logoAlto = Math.max(1, (int) Math.round(contenido.imagen().getHeight() * escala));
        BufferedImage chico = escalar(contenido.imagen(), logoAncho, logoAlto);

        int relleno = (int) Math.round(ancho * 0.025);
        int placaAncho = logoAncho + 2 * relleno;
        int placaAlto = logoAlto + 2 * relleno;

        int margenX = (int) Math.round(ancho * MARGEN_X);
        double proporcionY = !historia ? MARGEN_Y : posicion.arriba() ? MARGEN_Y_HISTORIA_ARRIBA : MARGEN_Y_HISTORIA_ABAJO;
        int margenY = (int) Math.round(alto * proporcionY);
        int x = posicion.izquierda() ? margenX
                : posicion.derecha() ? ancho - margenX - placaAncho
                : (ancho - placaAncho) / 2;
        int y = posicion.arriba() ? margenY : alto - margenY - placaAlto;

        BufferedImage salida = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(base, 0, 0, null);

            Color placa = contenido.claro() ? new Color(24, 28, 36, 224) : new Color(255, 255, 255, 236);
            g.setColor(placa);
            g.fill(new RoundRectangle2D.Double(x, y, placaAncho, placaAlto, relleno * 1.4, relleno * 1.4));
            g.drawImage(chico, x + relleno, y + relleno, null);
        } finally {
            g.dispose();
        }
        return aJpeg(salida);
    }

    /** El logo sin sus márgenes vacíos, y si sus colores son claros. */
    private record Contenido(BufferedImage imagen, boolean claro) {
    }

    /**
     * Recorta lo que es fondo: transparente o casi blanco. Deja un poco de aire
     * para que las letras no queden pegadas al borde de la placa.
     */
    private static Contenido recortarVacio(BufferedImage logo) {
        int ancho = logo.getWidth();
        int alto = logo.getHeight();
        int minX = ancho;
        int minY = alto;
        int maxX = -1;
        int maxY = -1;
        double luminancia = 0;
        long cuantos = 0;

        // Con transparencia, el fondo ES lo transparente y lo blanco es dibujo
        // (letras blancas sobre nada). Sin ella —un JPG, la hoja blanca de un
        // logo— el blanco es el fondo.
        boolean conTransparencia = false;
        for (int y = 0; y < alto && !conTransparencia; y++) {
            for (int x = 0; x < ancho; x++) {
                if (((logo.getRGB(x, y) >>> 24) & 0xFF) < 16) {
                    conTransparencia = true;
                    break;
                }
            }
        }

        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                int argb = logo.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int gg = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                boolean fondo = a < 16 || (!conTransparencia && r >= 240 && gg >= 240 && b >= 240);
                if (fondo) {
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                luminancia += 0.2126 * r + 0.7152 * gg + 0.0722 * b;
                cuantos++;
            }
        }
        if (cuantos == 0) {
            // Todo fondo: un logo blanco sobre transparente cae aquí solo si es
            // blanco puro y opaco, que no se distingue de nada. No hay qué pegar.
            throw new IllegalArgumentException("El logo está vacío.");
        }

        int aire = Math.max(2, (int) Math.round(Math.max(maxX - minX, maxY - minY) * 0.02));
        int x0 = Math.max(0, minX - aire);
        int y0 = Math.max(0, minY - aire);
        int x1 = Math.min(ancho - 1, maxX + aire);
        int y1 = Math.min(alto - 1, maxY + aire);

        BufferedImage recorte = new BufferedImage(x1 - x0 + 1, y1 - y0 + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = recorte.createGraphics();
        try {
            g.drawImage(logo, -x0, -y0, null);
        } finally {
            g.dispose();
        }
        return new Contenido(recorte, luminancia / cuantos > 190);
    }

    /** Achica de a mitades y termina con el tamaño exacto: de un salto sale dentado. */
    private static BufferedImage escalar(BufferedImage origen, int ancho, int alto) {
        BufferedImage actual = origen;
        int w = origen.getWidth();
        int h = origen.getHeight();
        while (w / 2 >= ancho && h / 2 >= alto) {
            w /= 2;
            h /= 2;
            actual = pasada(actual, w, h);
        }
        return pasada(actual, ancho, alto);
    }

    private static BufferedImage pasada(BufferedImage origen, int ancho, int alto) {
        BufferedImage destino = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(origen, 0, 0, ancho, alto, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    private static BufferedImage leer(byte[] bytes, String mensaje) {
        try {
            BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(bytes));
            if (imagen == null) {
                throw new IllegalArgumentException(mensaje);
            }
            return imagen;
        } catch (IOException ex) {
            throw new IllegalArgumentException(mensaje, ex);
        }
    }

    private static byte[] aJpeg(BufferedImage imagen) {
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                MemoryCacheImageOutputStream flujo = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametros.setCompressionQuality(0.92f);
            escritor.setOutput(flujo);
            escritor.write(null, new IIOImage(imagen, null, null), parametros);
            flujo.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar la imagen con el logo.", ex);
        } finally {
            escritor.dispose();
        }
    }
}

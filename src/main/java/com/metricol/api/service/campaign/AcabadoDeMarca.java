package com.metricol.api.service.campaign;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * El acabado de marca de una foto real, como lo haría un diseñador: encuadre,
 * y uno de tres estilos que elige el director de foto.
 *
 * <ul>
 * <li><b>LIMPIO</b>: la foto y el logo, nada más. Para fotos con mucho
 * detalle o que ya son fuertes: cualquier adorno les resta.</li>
 * <li><b>FRANJA</b>: una franja abajo que nace de un degradado suave, con el
 * logo y un rótulo corto ("Señalización industrial") y el nombre del
 * negocio. Para el portafolio de quien firma su trabajo.</li>
 * <li><b>MARCO</b>: un borde fino del color de la marca con un filete claro.
 * Para piezas elegantes, producto, interiores.</li>
 * <li><b>FRASE</b>: una frase de la marca sobre la foto, abajo, sobre un
 * degradado suave, con el logo chico. Para fotos personales o de paisaje
 * de una marca que motiva o cuenta su historia.</li>
 * </ul>
 *
 * <p><b>El logo, siempre sin fondo.</b> Si el archivo trae fondo blanco, se le
 * quita lo blanco que toca el borde (lo de adentro del logo se queda). Donde
 * no se leería, en vez de una placa lleva un degradado suave detrás.
 *
 * <p>Todo esto lo dibuja el sistema, nunca la IA de imágenes: un logo o un
 * texto redibujado por la IA sale deformado.
 */
final class AcabadoDeMarca {

    enum Estilo { LIMPIO, FRANJA, MARCO, FRASE;

        static Estilo de(String codigo) {
            try {
                return codigo == null ? LIMPIO : valueOf(codigo.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return LIMPIO;
            }
        }
    }

    /** Un recorte en proporciones de la foto (0 a 1). */
    record Encuadre(double x, double y, double ancho, double alto) {

        /** Solo recortes que valen: dentro de la foto y sin tirar más de lo que se ve. */
        Encuadre {
            x = Math.max(0, Math.min(1, x));
            y = Math.max(0, Math.min(1, y));
            ancho = Math.max(0, Math.min(1 - x, ancho));
            alto = Math.max(0, Math.min(1 - y, alto));
        }

        boolean vale() {
            return ancho * alto >= MIN_AREA && ancho * alto < 0.985;
        }
    }

    /**
     * @param estilo    cómo se viste
     * @param zona      dónde va el logo en LIMPIO y MARCO
     * @param anchoLogo ancho del logo respecto a la foto
     * @param encuadre  recorte, o nulo
     * @param rotulo    el texto corto de la franja ("Señalización industrial"), o nulo
     * @param negocio   el nombre, debajo del rótulo
     * @param historia  va de historia: sin franja (la interfaz de la red la tapa)
     */
    record Opciones(Estilo estilo, SelloDeLogo.Posicion zona, double anchoLogo, Encuadre encuadre, String rotulo,
            String negocio, boolean historia) {
    }

    /** Lo menos que se deja de la foto al encuadrar: más recorte ya es otra foto. */
    static final double MIN_AREA = 0.55;

    private static final Color AZUL_DE_RESPALDO = new Color(19, 41, 75);

    private AcabadoDeMarca() {
    }

    /**
     * @param foto la foto (ya mejorada, si se mejoró)
     * @param logo el archivo del logo, o nulo (sin logo: solo encuadre y estilo)
     * @return el JPEG final
     */
    static byte[] acabar(byte[] foto, byte[] logo, Opciones o) {
        BufferedImage base = SelloDeLogo.leer(foto, "La foto no se pudo leer.");
        if (o.encuadre() != null && o.encuadre().vale()) {
            base = recortar(base, o.encuadre());
        }
        BufferedImage marca = logo == null ? null
                : sinFondo(SelloDeLogo.leer(logo, "El logo no se puede leer: usa un archivo JPG o PNG."));
        Color color = colorDeMarca(logo);

        Estilo estilo = o.historia() && (o.estilo() == Estilo.FRANJA || o.estilo() == Estilo.FRASE) ? Estilo.LIMPIO
                : o.estilo();
        if (estilo == Estilo.FRASE) {
            return limpio(o.rotulo()).isEmpty() ? SelloDeLogo.aJpeg(marca == null ? base
                    : logoSuelto(base, marca, o.zona(), o.anchoLogo(), false))
                    : SelloDeLogo.aJpeg(frase(base, marca, color, o.rotulo()));
        }
        if (estilo == Estilo.FRANJA && marca == null && limpio(o.rotulo()).isEmpty()) {
            // Una franja sin logo ni rótulo es una mancha: limpia.
            estilo = Estilo.LIMPIO;
        }
        if (estilo == Estilo.FRANJA) {
            return SelloDeLogo.aJpeg(franja(base, marca, color, o.rotulo(), o.negocio()));
        }
        if (estilo == Estilo.MARCO) {
            base = marco(base, color);
        }
        if (marca == null) {
            return SelloDeLogo.aJpeg(base);
        }
        return SelloDeLogo.aJpeg(logoSuelto(base, marca, o.zona(), o.anchoLogo(), o.historia()));
    }

    // ------------------------------------------------------------------ logo

    /**
     * El logo sin su fondo: lo blanco (o casi) que toca el borde del archivo
     * se vuelve transparente, con una orilla suave. Lo blanco que el logo
     * encierra —el interior de un círculo, una letra— se queda.
     */
    static BufferedImage sinFondo(BufferedImage logo) {
        int w = logo.getWidth();
        int h = logo.getHeight();
        BufferedImage salida = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                salida.setRGB(x, y, logo.getRGB(x, y));
            }
        }
        if (SelloDeLogo.conTransparencia(salida)) {
            return salida;
        }
        boolean[] fondo = new boolean[w * h];
        ArrayDeque<int[]> pendientes = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            pendientes.add(new int[] {x, 0});
            pendientes.add(new int[] {x, h - 1});
        }
        for (int y = 0; y < h; y++) {
            pendientes.add(new int[] {0, y});
            pendientes.add(new int[] {w - 1, y});
        }
        while (!pendientes.isEmpty()) {
            int[] p = pendientes.poll();
            int x = p[0];
            int y = p[1];
            if (x < 0 || y < 0 || x >= w || y >= h || fondo[y * w + x] || !casiBlanco(salida.getRGB(x, y))) {
                continue;
            }
            fondo[y * w + x] = true;
            pendientes.add(new int[] {x + 1, y});
            pendientes.add(new int[] {x - 1, y});
            pendientes.add(new int[] {x, y + 1});
            pendientes.add(new int[] {x, y - 1});
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (fondo[y * w + x]) {
                    salida.setRGB(x, y, 0);
                } else if (tocaFondo(fondo, w, h, x, y)) {
                    // La orilla, a media opacidad: sin el serrucho de un recorte duro.
                    int argb = salida.getRGB(x, y);
                    salida.setRGB(x, y, (argb & 0x00FFFFFF) | (0x99 << 24));
                }
            }
        }
        return salida;
    }

    private static boolean casiBlanco(int argb) {
        return ((argb >> 16) & 0xFF) >= 232 && ((argb >> 8) & 0xFF) >= 232 && (argb & 0xFF) >= 232;
    }

    private static boolean tocaFondo(boolean[] fondo, int w, int h, int x, int y) {
        return (x > 0 && fondo[y * w + x - 1]) || (x < w - 1 && fondo[y * w + x + 1])
                || (y > 0 && fondo[(y - 1) * w + x]) || (y < h - 1 && fondo[(y + 1) * w + x]);
    }

    /**
     * El logo sin fondo sobre la foto, en la zona del director. Si contrasta,
     * directo; si el fondo es oscuro y el logo no se lee, su versión clara;
     * y si aún así no se leería, un degradado suave detrás —nunca una placa—.
     */
    static BufferedImage logoSuelto(BufferedImage base, BufferedImage logo, SelloDeLogo.Posicion zona,
            double anchoLogo, boolean historia) {
        BufferedImage recortado = recortarTransparente(logo);
        int ancho = base.getWidth();
        int alto = base.getHeight();
        double escala = Math.min(anchoLogo * ancho / recortado.getWidth(), 0.16 * alto / recortado.getHeight());
        int lw = Math.max(1, (int) Math.round(recortado.getWidth() * escala));
        int lh = Math.max(1, (int) Math.round(recortado.getHeight() * escala));

        SelloDeLogo.Posicion posicion = zona;
        if (SelloDeLogo.ocupacion(base, SelloDeLogo.rectangulo(ancho, alto, lw, lh, zona, historia))
                > SelloDeLogo.MUY_OCUPADO) {
            posicion = SelloDeLogo.sitioLimpio(base, lw, lh, zona, historia);
        }
        Rectangle sitio = SelloDeLogo.rectangulo(ancho, alto, lw, lh, posicion, historia);
        BufferedImage chico = SelloDeLogo.escalar(recortado, lw, lh);
        double fondo = SelloDeLogo.luzMedia(base, sitio);
        double luzLogo = SelloDeLogo.luzDelDibujo(chico);

        boolean degradado = false;
        if (Math.abs(fondo - luzLogo) < SelloDeLogo.CONTRASTE_MINIMO && fondo < SelloDeLogo.FONDO_OSCURO) {
            chico = SelloDeLogo.paraFondoOscuro(chico);
            luzLogo = SelloDeLogo.luzDelDibujo(chico);
        }
        if (Math.abs(fondo - luzLogo) < SelloDeLogo.CONTRASTE_MINIMO) {
            degradado = true;
        }

        BufferedImage salida = copia(base);
        Graphics2D g = salida.createGraphics();
        try {
            calidad(g);
            if (degradado) {
                // Oscuro detrás de un logo claro, claro detrás de uno oscuro: lo justo para leerlo.
                halo(g, sitio, luzLogo > 128 ? new Color(0, 0, 0) : new Color(255, 255, 255), 0.42f);
            }
            g.drawImage(chico, sitio.x, sitio.y, null);
        } finally {
            g.dispose();
        }
        return salida;
    }

    /** Un degradado radial que nace detrás del logo y se esfuma: separa sin verse como fondo. */
    private static void halo(Graphics2D g, Rectangle sitio, Color color, float opacidad) {
        float radio = (float) (Math.max(sitio.width, sitio.height) * 0.95);
        Point2D centro = new Point2D.Float(sitio.x + sitio.width / 2f, sitio.y + sitio.height / 2f);
        int a = Math.round(255 * opacidad);
        RadialGradientPaint pintura = new RadialGradientPaint(centro, radio, new float[] {0f, 0.55f, 1f},
                new Color[] {conAlfa(color, a), conAlfa(color, a / 2), conAlfa(color, 0)});
        g.setPaint(pintura);
        g.fillRect((int) (centro.getX() - radio), (int) (centro.getY() - radio), (int) (2 * radio), (int) (2 * radio));
    }

    // ------------------------------------------------------------------ estilos

    /**
     * La franja de marca: un degradado que sube suave desde el borde de abajo
     * hasta el color de la marca, una línea de acento, el logo y el rótulo.
     */
    static BufferedImage franja(BufferedImage base, BufferedImage logo, Color color, String rotulo, String negocio) {
        int ancho = base.getWidth();
        int alto = base.getHeight();
        int banda = Math.max(60, (int) Math.round(alto * 0.15));
        int degradado = (int) Math.round(banda * 0.9);
        Color oscuro = oscurecer(color, 0.55);

        BufferedImage salida = copia(base);
        Graphics2D g = salida.createGraphics();
        try {
            calidad(g);
            int arriba = alto - banda;
            // Poco degradado: de transparente al color, sin cortes.
            g.setPaint(new GradientPaint(0, arriba - degradado, conAlfa(oscuro, 0), 0, arriba, conAlfa(oscuro, 200)));
            g.fillRect(0, arriba - degradado, ancho, degradado);
            g.setPaint(new GradientPaint(0, arriba, conAlfa(oscuro, 200), 0, alto, conAlfa(oscuro, 235)));
            g.fillRect(0, arriba, ancho, banda);
            // El acento: una línea corta del color de la marca, como firma.
            int margen = (int) Math.round(ancho * 0.05);
            g.setColor(aclarar(color, 0.15));
            g.setStroke(new BasicStroke(Math.max(2f, alto * 0.004f)));
            g.drawLine(margen, arriba + (int) (banda * 0.12), margen + (int) (ancho * 0.08), arriba + (int) (banda * 0.12));

            int x = margen;
            if (logo != null) {
                BufferedImage recortado = recortarTransparente(logo);
                int lh = (int) Math.round(banda * 0.74);
                int lw = (int) Math.round(recortado.getWidth() * (double) lh / recortado.getHeight());
                lw = Math.min(lw, (int) (ancho * 0.36));
                lh = (int) Math.round(recortado.getHeight() * (double) lw / recortado.getWidth());
                BufferedImage chico = SelloDeLogo.escalar(recortado, lw, lh);
                if (SelloDeLogo.luzDelDibujo(chico) < 110) {
                    chico = SelloDeLogo.paraFondoOscuro(chico);
                }
                g.drawImage(chico, x, arriba + (banda - lh) / 2 + (int) (banda * 0.05), null);
                x += lw + (int) Math.round(ancho * 0.035);
            }

            int disponible = ancho - x - margen;
            String titulo = limpio(rotulo);
            String sub = limpio(negocio);
            int base1 = arriba + (int) Math.round(banda * (sub.isEmpty() ? 0.62 : 0.52));
            if (!titulo.isEmpty() && disponible > ancho * 0.2) {
                Font f = ajustar(g, fuente(true, banda * 0.30f), titulo, disponible);
                g.setFont(f);
                g.setColor(Color.WHITE);
                g.drawString(recortarTexto(g.getFontMetrics(), titulo, disponible), x, base1);
            }
            if (!sub.isEmpty() && disponible > ancho * 0.2) {
                Font f = ajustar(g, fuente(false, banda * 0.19f), sub, disponible);
                g.setFont(f);
                g.setColor(new Color(255, 255, 255, 200));
                int base2 = titulo.isEmpty() ? base1 : base1 + (int) Math.round(banda * 0.27);
                g.drawString(recortarTexto(g.getFontMetrics(), sub, disponible), x, base2);
            }
        } finally {
            g.dispose();
        }
        return salida;
    }

    /**
     * Una frase sobre la foto: un degradado oscuro que sube suave desde abajo,
     * unas comillas del color de la marca, la frase en blanco (hasta tres
     * renglones) y el logo chico en la esquina.
     */
    static BufferedImage frase(BufferedImage base, BufferedImage logo, Color color, String texto) {
        int ancho = base.getWidth();
        int alto = base.getHeight();
        int margen = (int) Math.round(ancho * 0.07);
        BufferedImage salida = copia(base);
        Graphics2D g = salida.createGraphics();
        try {
            calidad(g);
            Font f = fuente(true, Math.max(18f, ancho * 0.058f));
            g.setFont(f);
            List<String> renglones = renglones(g.getFontMetrics(), limpio(texto), ancho - 2 * margen, 3);
            if (renglones.size() == 3 && g.getFontMetrics().stringWidth(renglones.get(2)) > ancho - 2 * margen) {
                f = f.deriveFont(f.getSize2D() * 0.85f);
                g.setFont(f);
                renglones = renglones(g.getFontMetrics(), limpio(texto), ancho - 2 * margen, 3);
            }
            FontMetrics m = g.getFontMetrics();
            int interlinea = (int) Math.round(m.getHeight() * 1.05);
            int abajo = alto - (int) Math.round(alto * 0.07);
            int arribaTexto = abajo - interlinea * renglones.size();

            // Poco degradado: de nada a oscuro, empezando bastante arriba del texto.
            int desde = Math.max(0, arribaTexto - (int) Math.round(alto * 0.22));
            g.setPaint(new GradientPaint(0, desde, new Color(0, 0, 0, 0), 0, alto, new Color(0, 0, 0, 185)));
            g.fillRect(0, desde, ancho, alto - desde);

            // Las comillas, del color de la marca.
            Font comillas = fuente(true, f.getSize2D() * 2.2f);
            g.setFont(comillas);
            g.setColor(aclarar(color, 0.35));
            g.drawString("\u201C", margen - (int) (f.getSize2D() * 0.1), arribaTexto + (int) (f.getSize2D() * 0.55));

            g.setFont(f);
            g.setColor(Color.WHITE);
            int y = arribaTexto + m.getAscent();
            for (String r : renglones) {
                g.drawString(r, margen, y);
                y += interlinea;
            }

            if (logo != null) {
                BufferedImage recortado = recortarTransparente(logo);
                int lw = (int) Math.round(ancho * 0.16);
                int lh = (int) Math.round(recortado.getHeight() * (double) lw / recortado.getWidth());
                if (lh > alto * 0.09) {
                    lh = (int) Math.round(alto * 0.09);
                    lw = (int) Math.round(recortado.getWidth() * (double) lh / recortado.getHeight());
                }
                BufferedImage chico = SelloDeLogo.escalar(recortado, lw, lh);
                if (SelloDeLogo.luzDelDibujo(chico) < 110) {
                    chico = SelloDeLogo.paraFondoOscuro(chico);
                }
                // Arriba a la derecha: abajo ya está la frase.
                g.drawImage(chico, ancho - margen - lw, (int) Math.round(alto * 0.045), null);
            }
        } finally {
            g.dispose();
        }
        return salida;
    }

    /** Parte el texto en renglones que caben; el último lleva "…" si sobra. */
    static List<String> renglones(FontMetrics m, String texto, int disponible, int maximo) {
        List<String> salida = new java.util.ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split(" ")) {
            String prueba = actual.length() == 0 ? palabra : actual + " " + palabra;
            if (m.stringWidth(prueba) <= disponible || actual.length() == 0) {
                actual.setLength(0);
                actual.append(prueba);
            } else {
                salida.add(actual.toString());
                actual.setLength(0);
                actual.append(palabra);
            }
        }
        if (actual.length() > 0) {
            salida.add(actual.toString());
        }
        if (salida.size() > maximo) {
            List<String> cortado = new java.util.ArrayList<>(salida.subList(0, maximo));
            cortado.set(maximo - 1, recortarTexto(m, cortado.get(maximo - 1) + " " + salida.get(maximo), disponible));
            return cortado;
        }
        return salida;
    }

    /** Un marco fino del color de la marca con un filete claro por dentro; la foto entra completa. */
    static BufferedImage marco(BufferedImage base, Color color) {
        int ancho = base.getWidth();
        int alto = base.getHeight();
        int borde = Math.max(8, (int) Math.round(Math.min(ancho, alto) * 0.028));
        int filete = Math.max(2, borde / 6);
        BufferedImage salida = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            calidad(g);
            g.setColor(oscurecer(color, 0.85));
            g.fillRect(0, 0, ancho, alto);
            int iw = ancho - 2 * borde;
            int ih = alto - 2 * borde;
            // Cubrir el hueco sin deformar: escalar y recortar al centro.
            double escala = Math.max((double) iw / ancho, (double) ih / alto);
            int sw = (int) Math.round(ancho * escala);
            int sh = (int) Math.round(alto * escala);
            g.setClip(borde, borde, iw, ih);
            g.drawImage(base, borde - (sw - iw) / 2, borde - (sh - ih) / 2, sw, sh, null);
            g.setClip(null);
            g.setColor(new Color(255, 255, 255, 210));
            g.setStroke(new BasicStroke(filete));
            g.drawRect(borde - filete, borde - filete, iw + filete, ih + filete);
        } finally {
            g.dispose();
        }
        return salida;
    }

    static BufferedImage recortar(BufferedImage base, Encuadre e) {
        int x = (int) Math.round(e.x() * base.getWidth());
        int y = (int) Math.round(e.y() * base.getHeight());
        int w = Math.max(1, Math.min(base.getWidth() - x, (int) Math.round(e.ancho() * base.getWidth())));
        int h = Math.max(1, Math.min(base.getHeight() - y, (int) Math.round(e.alto() * base.getHeight())));
        return copia(base.getSubimage(x, y, w, h));
    }

    // ------------------------------------------------------------------ apoyo

    /** El color principal del logo, o un azul profundo si no tiene (o no hay logo). */
    static Color colorDeMarca(byte[] logo) {
        if (logo != null) {
            try {
                List<String> colores = PaletaDeLogo.dominantes(logo, 2);
                if (!colores.isEmpty()) {
                    return Color.decode(colores.get(0));
                }
            } catch (RuntimeException ignorado) {
                // Sin paleta: el de respaldo.
            }
        }
        return AZUL_DE_RESPALDO;
    }

    private static BufferedImage recortarTransparente(BufferedImage logo) {
        int minX = logo.getWidth();
        int minY = logo.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < logo.getHeight(); y++) {
            for (int x = 0; x < logo.getWidth(); x++) {
                if (((logo.getRGB(x, y) >>> 24) & 0xFF) > 16) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            throw new IllegalArgumentException("El logo está vacío.");
        }
        return logo.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static Font fuente(boolean negrita, float tamano) {
        String archivo = negrita ? "/fuentes/Montserrat-Bold.ttf" : "/fuentes/Montserrat-Medium.ttf";
        Font f = FUENTES.computeIfAbsent(archivo, AcabadoDeMarca::cargar);
        return f.deriveFont(tamano);
    }

    private static final java.util.Map<String, Font> FUENTES = new java.util.concurrent.ConcurrentHashMap<>();

    private static Font cargar(String archivo) {
        try (InputStream in = AcabadoDeMarca.class.getResourceAsStream(archivo)) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            }
        } catch (Exception ignorado) {
            // Sin la fuente de la marca, la del sistema.
        }
        return new Font(Font.SANS_SERIF, archivo.contains("Bold") ? Font.BOLD : Font.PLAIN, 12);
    }

    /** Achica la fuente hasta que el texto quepa, sin bajar del 60 %. */
    private static Font ajustar(Graphics2D g, Font f, String texto, int disponible) {
        Font actual = f;
        while (g.getFontMetrics(actual).stringWidth(texto) > disponible && actual.getSize2D() > f.getSize2D() * 0.6f) {
            actual = actual.deriveFont(actual.getSize2D() * 0.92f);
        }
        return actual;
    }

    private static String recortarTexto(FontMetrics m, String texto, int disponible) {
        if (m.stringWidth(texto) <= disponible) {
            return texto;
        }
        String t = texto;
        while (t.length() > 1 && m.stringWidth(t + "…") > disponible) {
            t = t.substring(0, t.length() - 1);
        }
        return t.strip() + "…";
    }

    private static String limpio(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
    }

    private static BufferedImage copia(BufferedImage origen) {
        BufferedImage salida = new BufferedImage(origen.getWidth(), origen.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            g.drawImage(origen, 0, 0, null);
        } finally {
            g.dispose();
        }
        return salida;
    }

    private static void calidad(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static Color conAlfa(Color c, int alfa) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alfa)));
    }

    private static Color oscurecer(Color c, double factor) {
        // Un color de marca claro (amarillo, verde limón) se oscurece más: el texto blanco tiene que leerse.
        double luz = SelloDeLogo.luz(c.getRGB());
        double f = luz > 150 ? factor * 0.7 : factor;
        return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f));
    }

    private static Color aclarar(Color c, double cuanto) {
        return new Color((int) (c.getRed() + (255 - c.getRed()) * cuanto),
                (int) (c.getGreen() + (255 - c.getGreen()) * cuanto), (int) (c.getBlue() + (255 - c.getBlue()) * cuanto));
    }

    static byte[] png(BufferedImage imagen) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(imagen, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

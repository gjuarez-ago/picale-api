package com.metricol.api.service.campaign;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
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
 * <p><b>Se ve como el logo, no como una tarjeta.</b> Antes iba sobre una placa
 * blanca con un margen ancho y, sobre una foto, esa placa era lo primero que
 * se veía: un parche blanco con el logo chico adentro. Ahora el logo se
 * recorta a lo que dibuja, se le redondean apenas las esquinas y el borde que
 * se le deja alrededor es del color de su propio fondo: un logo sobre hoja
 * blanca sale como un logo con esquinas suaves, y uno sobre fondo azul, azul.
 * Solo el logo transparente lleva placa propia —blanca, u oscura si el logo
 * es claro—, porque sin ella no se vería.
 *
 * <p><b>Busca un sitio limpio.</b> A la IA se le pide dejar libre el rincón
 * donde irá el logo y casi siempre obedece; cuando no, el logo caía encima
 * del titular. Antes de pegar se mide cuánto "pasa" debajo de cada rincón
 * —bordes, letras, contraste— y si el pedido está ocupado se usa el rincón
 * limpio más parecido: primero el otro lado de la misma fila, y solo después
 * la fila contraria. Si todos están igual de ocupados, el pedido manda.
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

        /**
         * Los demás rincones, del más parecido a este al menos: primero los de
         * la misma fila —el lado contrario antes que el centro—, después los de
         * la otra fila en el mismo orden. Así un logo pedido arriba se queda
         * arriba mientras haya dónde.
         */
        List<Posicion> alternativas() {
            return switch (this) {
                case TOP_LEFT -> List.of(TOP_RIGHT, TOP_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT, BOTTOM_CENTER);
                case TOP_CENTER -> List.of(TOP_RIGHT, TOP_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT, BOTTOM_LEFT);
                case TOP_RIGHT -> List.of(TOP_LEFT, TOP_CENTER, BOTTOM_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER);
                case BOTTOM_LEFT -> List.of(BOTTOM_RIGHT, BOTTOM_CENTER, TOP_LEFT, TOP_RIGHT, TOP_CENTER);
                case BOTTOM_CENTER -> List.of(BOTTOM_RIGHT, BOTTOM_LEFT, TOP_CENTER, TOP_RIGHT, TOP_LEFT);
                case BOTTOM_RIGHT -> List.of(BOTTOM_LEFT, BOTTOM_CENTER, TOP_RIGHT, TOP_LEFT, TOP_CENTER);
            };
        }
    }

    /** Lo más ancho que puede ser el logo, sin contar el borde, respecto al ancho de la imagen. */
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

    /**
     * El borde alrededor del dibujo del logo, respecto al ancho de la imagen.
     * Apenas: es el aire que trae un logo bien puesto, no una tarjeta. Antes
     * era el doble y el sello se leía como un parche blanco con el logo dentro.
     */
    private static final double BORDE = 0.012;

    /** Cuánto se redondean las esquinas, respecto al lado menor del sello. Poco: es un logo, no un botón. */
    private static final double REDONDEO = 0.14;

    /**
     * Por encima de esto, debajo del sello hay "algo": letras, un borde, un
     * objeto. Es el cambio medio de luz entre píxeles vecinos (de 0 a 255):
     * un cielo o un degradado dan 1 ó 2; letras sobre un panel, más de 20.
     */
    static final double OCUPADO = 8.0;

    private SelloDeLogo() {
    }

    /** La imagen con el logo puesto y dónde quedó, que puede no ser donde se pidió. */
    record Colocado(byte[] imagen, Posicion posicion) {
    }

    /** Como {@link #colocar}, cuando solo interesa la imagen. */
    static byte[] poner(byte[] imagen, byte[] logo, Posicion posicion, boolean historia) {
        return colocar(imagen, logo, posicion, historia).imagen();
    }

    /**
     * @param imagen la imagen ya recortada (JPEG, PNG…)
     * @param logo el archivo del logo tal como está en Contenido
     * @param pedida dónde se quiere el logo; se respeta salvo que ahí haya algo debajo
     * @param historia si el lienzo es una historia (9:16): margen vertical mayor
     * @return la imagen con el logo, en JPEG, y el rincón donde quedó
     * @throws IllegalArgumentException si el logo no se puede leer (WebP, por ejemplo)
     */
    static Colocado colocar(byte[] imagen, byte[] logo, Posicion pedida, boolean historia) {
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

        int borde = Math.max(4, (int) Math.round(ancho * BORDE));
        int selloAncho = logoAncho + 2 * borde;
        int selloAlto = logoAlto + 2 * borde;

        Posicion posicion = sitioLimpio(base, selloAncho, selloAlto, pedida, historia);
        Rectangle sitio = rectangulo(ancho, alto, selloAncho, selloAlto, posicion, historia);
        double radio = Math.min(selloAncho, selloAlto) * REDONDEO;
        Shape sello = new RoundRectangle2D.Double(sitio.x, sitio.y, selloAncho, selloAlto, radio, radio);

        BufferedImage salida = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(base, 0, 0, null);

            sombra(g, sitio, radio);
            g.setColor(contenido.fondo());
            g.fill(sello);
            // El logo se recorta con la misma forma: sus esquinas siguen las del sello.
            g.setClip(sello);
            g.drawImage(chico, sitio.x + borde, sitio.y + borde, null);
        } finally {
            g.dispose();
        }
        return new Colocado(aJpeg(salida), posicion);
    }

    /** Lo más alto que puede ser el logo sobre una foto, respecto a su alto. */
    private static final double ALTO_MAX_FOTO = 0.16;

    /** Diferencia de luz (0-255) entre logo y fondo para que se lea sin placa. */
    static final double CONTRASTE_MINIMO = 60;

    /** Más oscuro que esto (0-255), el fondo pide la versión clara del logo. */
    static final double FONDO_OSCURO = 125;

    /** Un píxel del logo más oscuro que esto se vuelve blanco en su versión para fondo oscuro. */
    private static final double TINTA_OSCURA = 100;

    /** Por encima de esto la zona elegida está llena de detalle: se busca otra. */
    static final double MUY_OCUPADO = OCUPADO * 3;

    /**
     * El logo sobre una foto real, como lo pondría un diseñador: en la zona
     * y del tamaño que eligió el director de foto, y <b>sin placa</b> cuando
     * el logo es transparente y se puede leer directo sobre la foto.
     *
     * <ul>
     * <li>Se mide la luz del fondo justo donde va. Si contrasta con el logo,
     * va directo, con una sombra apenas perceptible.</li>
     * <li>Si el fondo es oscuro y el logo no se leería, va su versión para
     * fondo oscuro: lo oscuro del logo (letras, contornos) pasa a blanco y los
     * colores de marca se quedan.</li>
     * <li>Un fondo claro con un logo claro, o un logo con fondo propio (JPG),
     * llevan la placa de siempre.</li>
     * </ul>
     *
     * @param zona       dónde lo quiere el director; si ahí hay mucho detalle, se busca otra
     * @param anchoFoto  ancho del logo respecto al ancho de la foto
     */
    static Colocado colocarSobreFoto(byte[] imagen, byte[] logo, Posicion zona, double anchoFoto, boolean historia) {
        BufferedImage base = leer(imagen, "La foto no se pudo leer.");
        BufferedImage original = leer(logo, "El logo no se puede leer: usa un archivo JPG o PNG.");
        if (!conTransparencia(original)) {
            return colocar(imagen, logo, zona, historia);
        }
        Contenido contenido = recortarVacio(original);
        int ancho = base.getWidth();
        int alto = base.getHeight();
        double escala = Math.min(anchoFoto * ancho / contenido.imagen().getWidth(),
                ALTO_MAX_FOTO * alto / contenido.imagen().getHeight());
        int logoAncho = Math.max(1, (int) Math.round(contenido.imagen().getWidth() * escala));
        int logoAlto = Math.max(1, (int) Math.round(contenido.imagen().getHeight() * escala));

        Posicion posicion = zona;
        if (ocupacion(base, rectangulo(ancho, alto, logoAncho, logoAlto, zona, historia)) > MUY_OCUPADO) {
            posicion = sitioLimpio(base, logoAncho, logoAlto, zona, historia);
        }
        Rectangle sitio = rectangulo(ancho, alto, logoAncho, logoAlto, posicion, historia);
        double fondo = luzMedia(base, sitio);
        BufferedImage chico = escalar(contenido.imagen(), logoAncho, logoAlto);
        double luzLogo = luzDelDibujo(chico);

        if (Math.abs(fondo - luzLogo) < CONTRASTE_MINIMO) {
            if (fondo >= FONDO_OSCURO) {
                // Fondo claro y logo claro: sin placa no se lee.
                return colocar(imagen, logo, posicion, historia);
            }
            chico = paraFondoOscuro(chico);
        }

        BufferedImage salida = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = salida.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(base, 0, 0, null);
            sombraDelDibujo(g, chico, sitio.x, sitio.y, Math.max(1, ancho / 600), luzDelDibujo(chico) > FONDO_OSCURO);
            g.drawImage(chico, sitio.x, sitio.y, null);
        } finally {
            g.dispose();
        }
        return new Colocado(aJpeg(salida), posicion);
    }

    static boolean conTransparencia(BufferedImage logo) {
        if (!logo.getColorModel().hasAlpha()) {
            return false;
        }
        for (int y = 0; y < logo.getHeight(); y++) {
            for (int x = 0; x < logo.getWidth(); x++) {
                if (((logo.getRGB(x, y) >>> 24) & 0xFF) < 16) {
                    return true;
                }
            }
        }
        return false;
    }

    /** La luz media de una zona de la foto (0-255), muestreada. */
    static double luzMedia(BufferedImage imagen, Rectangle zona) {
        int x0 = Math.max(0, zona.x);
        int y0 = Math.max(0, zona.y);
        int x1 = Math.min(imagen.getWidth(), zona.x + zona.width);
        int y1 = Math.min(imagen.getHeight(), zona.y + zona.height);
        int paso = Math.max(1, Math.min(zona.width, zona.height) / 30);
        double suma = 0;
        long n = 0;
        for (int y = y0; y < y1; y += paso) {
            for (int x = x0; x < x1; x += paso) {
                suma += luz(imagen.getRGB(x, y));
                n++;
            }
        }
        return n == 0 ? 128 : suma / n;
    }

    /** La luz media de lo que dibuja el logo (sin lo transparente), pesada por su opacidad. */
    static double luzDelDibujo(BufferedImage logo) {
        double suma = 0;
        double peso = 0;
        for (int y = 0; y < logo.getHeight(); y++) {
            for (int x = 0; x < logo.getWidth(); x++) {
                int argb = logo.getRGB(x, y);
                double a = ((argb >>> 24) & 0xFF) / 255.0;
                if (a > 0.06) {
                    suma += luz(argb) * a;
                    peso += a;
                }
            }
        }
        return peso == 0 ? 128 : suma / peso;
    }

    /**
     * La versión del logo para fondo oscuro: lo oscuro (letras, contornos,
     * iconos en azul marino o negro) pasa a blanco con su misma opacidad; los
     * colores de marca claros se quedan como están.
     */
    static BufferedImage paraFondoOscuro(BufferedImage logo) {
        BufferedImage salida = new BufferedImage(logo.getWidth(), logo.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < logo.getHeight(); y++) {
            for (int x = 0; x < logo.getWidth(); x++) {
                int argb = logo.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                salida.setRGB(x, y, a > 0 && luz(argb) < TINTA_OSCURA ? (a << 24) | 0xFFFFFF : argb);
            }
        }
        return salida;
    }

    /**
     * Una sombra suave con la forma del logo: lo despega de la foto sin que se
     * note como sombra. Oscura bajo un logo claro; bajo uno oscuro, apenas.
     */
    private static void sombraDelDibujo(Graphics2D g, BufferedImage logo, int x, int y, int radio, boolean logoClaro) {
        BufferedImage sombra = new BufferedImage(logo.getWidth(), logo.getHeight(), BufferedImage.TYPE_INT_ARGB);
        // Muchas copias corridas apiladas: cada una aporta poco, para que en el
        // centro sumen la opacidad buscada (1 - (1 - p)^n) y en el borde se esfume.
        int copias = (2 * radio + 2) * (2 * radio + 1);
        double total = logoClaro ? 0.32 : 0.12;
        double porCopia = 1 - Math.pow(1 - total, 1.0 / copias);
        for (int j = 0; j < logo.getHeight(); j++) {
            for (int i = 0; i < logo.getWidth(); i++) {
                int a = (logo.getRGB(i, j) >>> 24) & 0xFF;
                int alfa = (int) Math.round(a * porCopia);
                sombra.setRGB(i, j, Math.max(0, Math.min(255, alfa)) << 24);
            }
        }
        for (int dy = -radio; dy <= radio + 1; dy++) {
            for (int dx = -radio; dx <= radio; dx++) {
                g.drawImage(sombra, x + dx, y + dy + radio, null);
            }
        }
    }

    /**
     * Una sombra corta y tenue, como la de una tarjeta sobre una mesa: separa
     * el sello de la foto sin que la sombra se note por sí misma.
     */
    private static void sombra(Graphics2D g, Rectangle sitio, double radio) {
        int capas = 4;
        for (int i = capas; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, 6 + 3 * (capas - i)));
            g.fill(new RoundRectangle2D.Double(sitio.x - i, sitio.y - i + 2.0, sitio.width + 2.0 * i,
                    sitio.height + 2.0 * i, radio + i, radio + i));
        }
    }

    /** Dónde cae el sello para una posición, en la imagen ya recortada. */
    static Rectangle rectangulo(int ancho, int alto, int selloAncho, int selloAlto, Posicion posicion,
            boolean historia) {
        int margenX = (int) Math.round(ancho * MARGEN_X);
        double proporcionY = !historia ? MARGEN_Y
                : posicion.arriba() ? MARGEN_Y_HISTORIA_ARRIBA : MARGEN_Y_HISTORIA_ABAJO;
        int margenY = (int) Math.round(alto * proporcionY);
        int x = posicion.izquierda() ? margenX
                : posicion.derecha() ? ancho - margenX - selloAncho
                : (ancho - selloAncho) / 2;
        int y = posicion.arriba() ? margenY : alto - margenY - selloAlto;
        return new Rectangle(x, y, selloAncho, selloAlto);
    }

    /**
     * El rincón donde va a quedar el sello: el pedido si está limpio, y si no
     * el más limpio de los demás, en el orden de {@link Posicion#alternativas}.
     *
     * <p>Se cambia de sitio solo cuando hay uno claramente mejor: si todos
     * están igual de ocupados —una foto llena de detalle por todos lados—,
     * moverlo no gana nada y lo pedido manda.
     */
    static Posicion sitioLimpio(BufferedImage base, int selloAncho, int selloAlto, Posicion pedida,
            boolean historia) {
        double enPedida = ocupacion(base,
                rectangulo(base.getWidth(), base.getHeight(), selloAncho, selloAlto, pedida, historia));
        if (enPedida <= OCUPADO) {
            return pedida;
        }
        Posicion mejor = pedida;
        double menor = enPedida;
        for (Posicion otra : pedida.alternativas()) {
            double ocupacion = ocupacion(base,
                    rectangulo(base.getWidth(), base.getHeight(), selloAncho, selloAlto, otra, historia));
            if (ocupacion < menor) {
                menor = ocupacion;
                mejor = otra;
            }
        }
        return menor <= OCUPADO || menor < enPedida / 2 ? mejor : pedida;
    }

    /**
     * Cuánto "pasa" en una zona de la imagen: el cambio medio de luz entre
     * píxeles vecinos, de 0 (color liso) a 255. Letras, el borde de un panel o
     * un objeto con contraste suben el número; un cielo, una pared o un
     * degradado lo dejan cerca de cero.
     *
     * <p>Se mira un poco más que el sello, porque lo que lo roza también
     * estorba, y se muestrea: medir cada píxel de 1024 × 1280 por seis
     * rincones sería trabajo para nada.
     */
    static double ocupacion(BufferedImage imagen, Rectangle zona) {
        int holgura = Math.max(4, Math.min(zona.width, zona.height) / 10);
        int x0 = Math.max(0, zona.x - holgura);
        int y0 = Math.max(0, zona.y - holgura);
        int x1 = Math.min(imagen.getWidth() - 1, zona.x + zona.width + holgura);
        int y1 = Math.min(imagen.getHeight() - 1, zona.y + zona.height + holgura);
        int paso = Math.max(1, Math.min(zona.width, zona.height) / 40);

        double suma = 0;
        long pares = 0;
        for (int y = y0; y + paso <= y1; y += paso) {
            for (int x = x0; x + paso <= x1; x += paso) {
                double luz = luz(imagen.getRGB(x, y));
                suma += Math.abs(luz - luz(imagen.getRGB(x + paso, y)));
                suma += Math.abs(luz - luz(imagen.getRGB(x, y + paso)));
                pares += 2;
            }
        }
        return pares == 0 ? 0 : suma / pares;
    }

    static double luz(int rgb) {
        return 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
    }

    /** El logo sin sus márgenes vacíos, y el color del borde que se le deja alrededor. */
    private record Contenido(BufferedImage imagen, Color fondo) {
    }

    /**
     * Recorta lo que es fondo: transparente o casi blanco. Deja un poco de aire
     * para que las letras no queden pegadas al borde del sello.
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

        boolean claro = luminancia / cuantos > 190;
        Color fondo = conTransparencia
                // Sin fondo propio hace falta uno: blanco, u oscuro si el logo es claro y se perdería.
                ? (claro ? new Color(24, 28, 36, 224) : new Color(255, 255, 255, 236))
                // Con fondo propio, el borde sigue ese fondo: el sello se ve como el logo, no como una placa.
                : colorDelBorde(recorte);
        return new Contenido(recorte, fondo);
    }

    /** El color medio del contorno de un logo opaco: su fondo, sea hoja blanca o color de marca. */
    private static Color colorDelBorde(BufferedImage imagen) {
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        int ancho = imagen.getWidth();
        int alto = imagen.getHeight();
        for (int x = 0; x < ancho; x++) {
            for (int y : new int[] {0, alto - 1}) {
                int rgb = imagen.getRGB(x, y);
                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
                n++;
            }
        }
        for (int y = 1; y < alto - 1; y++) {
            for (int x : new int[] {0, ancho - 1}) {
                int rgb = imagen.getRGB(x, y);
                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
                n++;
            }
        }
        return n == 0 ? Color.WHITE : new Color((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /** Achica de a mitades y termina con el tamaño exacto: de un salto sale dentado. */
    static BufferedImage escalar(BufferedImage origen, int ancho, int alto) {
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

    static BufferedImage leer(byte[] bytes, String mensaje) {
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

    static byte[] aJpeg(BufferedImage imagen) {
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

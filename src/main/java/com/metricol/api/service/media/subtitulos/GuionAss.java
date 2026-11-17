package com.metricol.api.service.media.subtitulos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Escribe los subtítulos con estilo en formato ASS, que es lo que ffmpeg
 * (libass) sabe pintar encima del video: letra, color, contorno, posición y
 * animaciones por palabra.
 *
 * <p>Las medidas salen del tamaño real del video y no de números fijos: el
 * mismo estilo tiene que verse igual en un reel vertical y en un video
 * horizontal. En vertical el texto va más arriba que en la tele, porque abajo
 * TikTok, Instagram y Facebook ponen el nombre, la descripción y los botones.
 *
 * <p>Encima de los subtítulos van dos cosas más, las dos opcionales: las
 * palabras clave ({@link Palabra#clave()}), que se pintan en color y más
 * grandes, y los letreros informativos ({@link Letrero}), que son recuadros
 * aparte con la idea del momento.
 */
public final class GuionAss {

    /** Lo que dura en pantalla la última palabra de un trozo después de dicha. */
    static final double COLA = 0.3;

    /** El color de resaltado cuando la marca no trae uno que se lea sobre video. */
    static final String AMARILLO = "#FFD400";

    private static final String BLANCO = "&H00FFFFFF&";
    private static final String TINTA = "&H00141414&";

    private GuionAss() {
    }

    /**
     * @param color el color de la marca para resaltar ("#1A3A6B"); si es muy
     *              oscuro para leerse encima de un video, se usa amarillo
     */
    public static String escribir(List<Palabra> palabras, EstiloSubtitulos estilo, int ancho, int alto,
            String color) {
        return escribir(palabras, estilo, ancho, alto, color, List.of());
    }

    /** @param letreros los recuadros informativos; vacía o nula = solo subtítulos */
    public static String escribir(List<Palabra> palabras, EstiloSubtitulos estilo, int ancho, int alto,
            String color, List<Letrero> letreros) {
        return escribir(palabras, estilo, ancho, alto, color, letreros, null);
    }

    /** @param diseno colores, letra, tamaño de la cuenta; nulo = lo de siempre */
    public static String escribir(List<Palabra> palabras, EstiloSubtitulos estilo, int ancho, int alto,
            String color, List<Letrero> letreros, DisenoVideo diseno) {
        DisenoVideo d = diseno == null ? DisenoVideo.DE_SIEMPRE : diseno.limpio();
        boolean vertical = alto > ancho;
        int base = Math.min(ancho, alto);
        // Un color elegido a mano se respeta tal cual; el de la marca pasa por el filtro de que se lea.
        String resalte = colorAss(d.resalte() != null ? d.resalte() : resalteLegible(color));
        String letra = d.letraONormal().nombre(false);
        double escala = d.letraONormal().factor() * d.tamanoONormal().factor();
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\nScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\n")
                .append("PlayResX: ").append(ancho).append("\nPlayResY: ").append(alto).append("\n\n");
        ass.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, ")
                .append("BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, ")
                .append("Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        int margenLados = (int) Math.round(ancho * 0.08);
        int abajo;
        switch (estilo) {
            case PALABRA, CAJA -> {
                int tam = (int) Math.round(base * 0.075 * escala);
                abajo = (int) Math.round(alto * (vertical ? 0.30 : 0.12));
                estilo(ass, "Default", letra, tam, "&H00FFFFFF", "&H00000000", "&H64000000", 1,
                        Math.max(2, tam * 0.10), Math.max(1, tam * 0.04), margenLados, abajo);
                if (estilo == EstiloSubtitulos.CAJA) {
                    // La misma letra con BorderStyle 3: de ella solo se ve la caja de la palabra que suena.
                    estilo(ass, "Caja", letra, tam, "&H00FFFFFF", "&H00000000", "&H00000000", 3,
                            Math.max(3, tam * 0.16), 0, margenLados, abajo);
                }
            }
            case REBOTE -> {
                int tam = (int) Math.round(base * 0.11 * escala);
                abajo = (int) Math.round(alto * (vertical ? 0.32 : 0.14));
                estilo(ass, "Default", d.letraONormal().nombre(true), tam, "&H00FFFFFF", "&H00000000", "&H64000000", 1,
                        Math.max(3, tam * 0.11), Math.max(1, tam * 0.05), margenLados, abajo);
            }
            default -> {
                int tam = (int) Math.round(base * 0.05 * escala);
                abajo = (int) Math.round(alto * (vertical ? 0.20 : 0.07));
                // BorderStyle 3: una franja detrás del texto, semitransparente.
                estilo(ass, "Default", letra, tam, "&H00FFFFFF", "&H8C000000", "&H8C000000", 3,
                        Math.max(4, tam * 0.30), 0, margenLados, abajo);
            }
        }
        boolean hayLetreros = letreros != null && !letreros.isEmpty();
        int tamLetrero = (int) Math.round(base * 0.066);
        if (hayLetreros) {
            estilo(ass, "Letrero", "Montserrat ExtraBold", tamLetrero, "&H00FFFFFF", "&H00000000", "&H00000000", 1,
                    0, 0, 0, 0);
        }
        ass.append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        boolean hayClaves = palabras.stream().anyMatch(Palabra::clave);
        // Entra subiendo un poco y aclarándose: aparecer de golpe se ve a programa viejo.
        int cx = ancho / 2;
        int piso = alto - abajo;
        int salto = Math.max(4, (int) Math.round(base * 0.012));
        String entrada = "{\\an2\\move(" + cx + "," + (piso + salto) + "," + cx + "," + piso + ",0,110)\\fad(70,0)}";
        List<List<Palabra>> trozos = trozos(palabras, estilo, d.tamanoONormal().factor());
        for (int t = 0; t < trozos.size(); t++) {
            List<Palabra> trozo = trozos.get(t);
            double siguiente = t + 1 < trozos.size() ? trozos.get(t + 1).get(0).inicio() : Double.MAX_VALUE;
            double finTrozo = Math.min(trozo.get(trozo.size() - 1).fin() + COLA, siguiente);
            switch (estilo) {
                case PALABRA, CAJA -> {
                    // Un evento por palabra: el trozo entero, con la que suena resaltada.
                    boolean primero = true;
                    for (int i = 0; i < trozo.size(); i++) {
                        double desde = trozo.get(i).inicio();
                        double hasta = i + 1 < trozo.size() ? trozo.get(i + 1).inicio() : finTrozo;
                        if (hasta - desde < 0.01) continue;
                        String prefijo = primero ? entrada : "";
                        primero = false;
                        if (estilo == EstiloSubtitulos.CAJA) {
                            evento(ass, 0, desde, hasta, "Caja", prefijo + cajaDe(trozo, i, resalte));
                            evento(ass, 1, desde, hasta, "Default", prefijo + sobreCaja(trozo, i, resalte));
                        } else {
                            evento(ass, 0, desde, hasta, "Default", prefijo + karaoke(trozo, i, resalte));
                        }
                    }
                }
                case REBOTE -> {
                    // Entra chica y ladeada, se pasa tantito y se asienta; cada trozo se ladea al otro lado.
                    StringBuilder texto = new StringBuilder("{\\fscx70\\fscy70\\t(0,90,\\fscx110\\fscy110)"
                            + "\\t(90,170,\\fscx100\\fscy100)\\frz" + (t % 2 == 0 ? "-3" : "3")
                            + "\\t(0,170,\\frz0)\\fad(0,60)}");
                    for (int j = 0; j < trozo.size(); j++) {
                        if (j > 0) texto.append(' ');
                        String palabra = limpio(trozo.get(j).texto()).toUpperCase(Locale.forLanguageTag("es"));
                        // Con palabras clave, esas van en color. Sin ellas, cada tercer trozo: da ritmo sin cansar.
                        boolean enColor = hayClaves ? trozo.get(j).clave() : t % 3 == 2;
                        texto.append(enColor ? "{\\c" + resalte + "}" + palabra + "{\\c&H00FFFFFF&}" : palabra);
                    }
                    evento(ass, 0, trozo.get(0).inicio(), finTrozo, "Default", texto.toString());
                }
                default -> {
                    StringBuilder texto = new StringBuilder("{\\fad(120,80)}");
                    for (int j = 0; j < trozo.size(); j++) {
                        if (j > 0) texto.append(' ');
                        String palabra = limpio(trozo.get(j).texto());
                        texto.append(trozo.get(j).clave() ? "{\\c" + resalte + "}" + palabra + "{\\c&H00FFFFFF&}"
                                : palabra);
                    }
                    evento(ass, 0, trozo.get(0).inicio(), finTrozo, "Default", texto.toString());
                }
            }
        }
        if (hayLetreros) {
            for (Letrero letrero : letreros) {
                letrero(ass, letrero, ancho, alto, tamLetrero,
                        d.colorLetreros() != null ? d.colorLetreros() : colorDe(resalte));
            }
        }
        return ass.toString();
    }

    /** El trozo con la palabra que suena en color y un poco más grande; las clave, siempre en color. */
    private static String karaoke(List<Palabra> trozo, int activa, String resalte) {
        StringBuilder texto = new StringBuilder();
        for (int j = 0; j < trozo.size(); j++) {
            if (j > 0) texto.append(' ');
            String palabra = limpio(trozo.get(j).texto());
            if (trozo.get(j).clave()) {
                // La clave crece con un saltito cuando le toca y se queda en color todo el trozo.
                texto.append(j == activa
                        ? "{\\c" + resalte + "\\fscx100\\fscy100\\t(0,90,\\fscx126\\fscy126)}" + palabra + "{\\r}"
                        : "{\\c" + resalte + "}" + palabra + "{\\r}");
            } else {
                texto.append(j == activa
                        ? "{\\c" + resalte + "\\fscx112\\fscy112}" + palabra + "{\\r}"
                        : palabra);
            }
        }
        return texto.toString();
    }

    /** Capa de abajo del estilo de caja: todo invisible menos la caja de color de la palabra que suena. */
    private static String cajaDe(List<Palabra> trozo, int activa, String resalte) {
        StringBuilder texto = new StringBuilder("{\\alpha&HFF&}");
        for (int j = 0; j < trozo.size(); j++) {
            if (j > 0) texto.append(' ');
            String palabra = limpio(trozo.get(j).texto());
            texto.append(j == activa
                    ? "{\\3a&H00&\\3c" + resalte + "}" + palabra + "{\\3a&HFF&}"
                    : palabra);
        }
        return texto.toString();
    }

    /** Capa de arriba del estilo de caja: el texto; sobre la caja va oscuro y sin contorno. */
    private static String sobreCaja(List<Palabra> trozo, int activa, String resalte) {
        StringBuilder texto = new StringBuilder();
        for (int j = 0; j < trozo.size(); j++) {
            if (j > 0) texto.append(' ');
            String palabra = limpio(trozo.get(j).texto());
            if (j == activa) {
                texto.append("{\\c").append(TINTA).append("\\3a&HFF&\\4a&HFF&}").append(palabra).append("{\\r}");
            } else if (trozo.get(j).clave()) {
                texto.append("{\\c").append(resalte).append("}").append(palabra).append("{\\r}");
            } else {
                texto.append(palabra);
            }
        }
        return texto.toString();
    }

    /**
     * Un letrero: sombra, recuadro de esquinas redondas y texto, los tres
     * entrando juntos desde arriba. El recuadro se dibuja a la medida del
     * texto con los anchos reales de la letra; libass no sabe redondear la
     * franja que pone él.
     */
    private static void letrero(StringBuilder ass, Letrero letrero, int ancho, int alto, int tam, String color) {
        List<String> lineas = renglones(limpio(letrero.texto()).toUpperCase(Locale.forLanguageTag("es")));
        if (lineas.isEmpty() || letrero.fin() - letrero.inicio() < 0.3) return;
        double anchoTexto = lineas.stream().mapToDouble(l -> anchoDe(l, tam)).max().orElse(0);
        double rellenoX = tam * 0.62;
        double rellenoY = tam * 0.42;
        double w = Math.min(ancho * 0.92, anchoTexto + 2 * rellenoX);
        double h = lineas.size() * tam + 2 * rellenoY;
        boolean vertical = alto > ancho;
        double centro = letrero.altura() != null ? letrero.altura() : vertical ? 0.115 : 0.12;
        double cy = Math.max(h / 2 + alto * 0.03, Math.min(alto - h / 2 - alto * 0.03, alto * centro));
        double cx = ancho / 2.0;
        double x0 = cx - w / 2;
        double y0 = cy - h / 2;
        String fondo;
        String tinta;
        switch (letrero.tipo() == null ? Letrero.Tipo.CLAVE : letrero.tipo()) {
            case TITULO -> { fondo = BLANCO; tinta = TINTA; }
            case AVISO -> { fondo = "&H00303BFF&"; tinta = BLANCO; }
            case CIERRE -> { fondo = TINTA; tinta = BLANCO; }
            // Sobre un color claro, tinta oscura; sobre uno oscuro, blanca.
            default -> { fondo = colorAss(color); tinta = luz(color) >= 0.45 ? TINTA : BLANCO; }
        }
        int caida = (int) Math.round(tam * 0.9);
        String mover = "\\move(0," + (-caida) + ",0,0,0,220)\\fad(140,160)";
        String forma = redondeado(x0, y0, w, h, Math.min(h / 2, tam * 0.5));
        int hondo = Math.max(2, (int) Math.round(tam * 0.10));
        // Sombra suave debajo: despega el letrero del video sin ponerle un borde duro.
        evento(ass, 4, letrero.inicio(), letrero.fin(), "Letrero", "{\\an7" + mover.replace("\\move(0," + (-caida)
                + ",0,0,", "\\move(0," + (hondo - caida) + ",0," + hondo + ",") + "\\c&H000000&\\1a&H78&\\blur"
                + Math.max(3, tam / 6) + "\\p1}" + forma + "{\\p0}");
        evento(ass, 5, letrero.inicio(), letrero.fin(), "Letrero", "{\\an7" + mover + "\\c" + fondo + "\\p1}"
                + forma + "{\\p0}");
        int tx = (int) Math.round(cx);
        int ty = (int) Math.round(cy);
        evento(ass, 6, letrero.inicio(), letrero.fin(), "Letrero", "{\\an5\\move(" + tx + "," + (ty - caida) + ","
                + tx + "," + ty + ",0,220)\\fad(140,160)\\c" + tinta + "}" + String.join("\\N", lineas));
    }

    /** Un rectángulo de esquinas redondas como dibujo ASS, en píxeles del video. */
    static String redondeado(double x, double y, double w, double h, double r) {
        double k = r * 0.5523; // lo que hace que una curva de Bézier parezca un cuarto de círculo
        double x2 = x + w;
        double y2 = y + h;
        return String.format(Locale.US,
                "m %.0f %.0f l %.0f %.0f b %.0f %.0f %.0f %.0f %.0f %.0f l %.0f %.0f b %.0f %.0f %.0f %.0f %.0f %.0f "
                        + "l %.0f %.0f b %.0f %.0f %.0f %.0f %.0f %.0f l %.0f %.0f b %.0f %.0f %.0f %.0f %.0f %.0f",
                x + r, y, x2 - r, y, x2 - r + k, y, x2, y + r - k, x2, y + r,
                x2, y2 - r, x2, y2 - r + k, x2 - r + k, y2, x2 - r, y2,
                x + r, y2, x + r - k, y2, x, y2 - r + k, x, y2 - r,
                x, y + r, x, y + r - k, x + r - k, y, x + r, y);
    }

    /** El texto del letrero en uno o dos renglones parejos; lo que no cabe se queda fuera. */
    static List<String> renglones(String texto) {
        if (texto.isEmpty()) return List.of();
        if (texto.length() > Letrero.MAX_LETRAS) {
            int corte = texto.lastIndexOf(' ', Letrero.MAX_LETRAS);
            texto = texto.substring(0, corte > 0 ? corte : Letrero.MAX_LETRAS).strip();
        }
        if (texto.length() <= 20 || texto.indexOf(' ') < 0) return List.of(texto);
        // Se parte en el espacio que deja los dos renglones más parecidos.
        int mejor = -1;
        for (int i = texto.indexOf(' '); i >= 0; i = texto.indexOf(' ', i + 1)) {
            if (mejor < 0 || Math.abs(texto.length() - 2 * i) < Math.abs(texto.length() - 2 * mejor)) mejor = i;
        }
        return List.of(texto.substring(0, mejor).strip(), texto.substring(mejor + 1).strip());
    }

    // Anchos de Montserrat ExtraBold en milésimas de su cuadro, sacados del archivo de la letra.
    private static final String LETRAS = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int[] ANCHOS = { 291, 301, 462, 730, 647, 897, 752, 242, 369, 369, 453, 609, 283, 388, 283,
            415, 685, 405, 599, 603, 700, 607, 649, 632, 669, 649, 283, 283, 609, 609, 609, 597, 1036, 786, 769, 730,
            826, 672, 642, 770, 806, 339, 557, 752, 610, 954, 806, 846, 737, 846, 740, 647, 635, 786, 766, 1184, 737,
            693, 679 };

    /**
     * Lo que mide un renglón en mayúsculas, en píxeles. El tamaño de ASS es la
     * altura del renglón completo; el cuadro de la letra es 0.64 de eso en
     * Montserrat (1000 de 1562 unidades).
     */
    static double anchoDe(String renglon, int tam) {
        double milesimas = 0;
        for (int i = 0; i < renglon.length(); i++) {
            char c = sinAcento(renglon.charAt(i));
            int donde = LETRAS.indexOf(c);
            milesimas += donde >= 0 ? ANCHOS[donde] : c == '¿' ? 597 : c == '¡' ? 301 : 800;
        }
        return milesimas / 1000.0 * tam * 0.64;
    }

    private static char sinAcento(char c) {
        return switch (c) {
            case 'Á' -> 'A';
            case 'É' -> 'E';
            case 'Í' -> 'I';
            case 'Ó' -> 'O';
            case 'Ú', 'Ü' -> 'U';
            case 'Ñ' -> 'N';
            default -> c;
        };
    }

    /** Cuántas palabras caben a la vez en cada estilo; una pausa siempre corta. */
    static List<List<Palabra>> trozos(List<Palabra> palabras, EstiloSubtitulos estilo) {
        return trozos(palabras, estilo, 1);
    }

    /** @param tamano con letra más grande caben menos letras por trozo, y con más chica, más */
    static List<List<Palabra>> trozos(List<Palabra> palabras, EstiloSubtitulos estilo, double tamano) {
        int maxPalabras = switch (estilo) {
            case PALABRA, CAJA -> 3;
            case REBOTE -> 2;
            case CLASICO -> 7;
        };
        int maxLetras = (int) Math.round(switch (estilo) {
            case PALABRA, CAJA -> 18;
            case REBOTE -> 14;
            case CLASICO -> 40;
        } / tamano);
        List<List<Palabra>> trozos = new ArrayList<>();
        List<Palabra> actual = new ArrayList<>();
        int letras = 0;
        for (Palabra p : palabras) {
            if (limpio(p.texto()).isEmpty()) continue;
            boolean corta = !actual.isEmpty() && (actual.size() >= maxPalabras
                    || letras + 1 + p.texto().length() > maxLetras
                    || p.inicio() - actual.get(actual.size() - 1).fin() > 0.5);
            if (corta) {
                trozos.add(actual);
                actual = new ArrayList<>();
                letras = 0;
            }
            letras += (actual.isEmpty() ? 0 : 1) + p.texto().length();
            actual.add(p);
        }
        if (!actual.isEmpty()) trozos.add(actual);
        return trozos;
    }

    private static void estilo(StringBuilder ass, String nombre, String letra, int tam, String texto,
            String contorno, String sombra, int borde, double grosor, double profundidad, int lados, int abajo) {
        ass.append(String.format(Locale.US,
                "Style: %s,%s,%d,%s,%s,%s,%s,0,0,0,0,100,100,0,0,%d,%.1f,%.1f,2,%d,%d,%d,1%n",
                nombre, letra, tam, texto, texto, contorno, sombra, borde, grosor, profundidad, lados, lados, abajo));
    }

    private static void evento(StringBuilder ass, int capa, double desde, double hasta, String estilo, String texto) {
        ass.append("Dialogue: ").append(capa).append(',').append(tiempo(desde)).append(',').append(tiempo(hasta))
                .append(',').append(estilo).append(",,0,0,0,,").append(texto).append('\n');
    }

    /** "0:00:01.25": centésimas, que es lo que entiende ASS. */
    static String tiempo(double segundos) {
        long cs = Math.round(Math.max(0, segundos) * 100);
        return String.format(Locale.US, "%d:%02d:%02d.%02d", cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100);
    }

    /** Sin llaves ni barras: en ASS son órdenes, y una palabra no debe poder darlas. */
    static String limpio(String texto) {
        return texto == null ? "" : texto.replaceAll("[{}\\\\]", "").replaceAll("\\s+", " ").strip();
    }

    /**
     * El color de la marca que resalta: de sus colores ("#030202,#F3DA97,#D4A858"),
     * el más vivo entre los que se leen sobre un video. Uno oscuro se pierde
     * sobre la imagen y uno casi blanco no se distingue del texto blanco; si
     * no queda ninguno, amarillo.
     */
    static String resalteLegible(String colores) {
        if (colores == null) return AMARILLO;
        String mejor = null;
        double masVivo = -1;
        for (String crudo : colores.split("[,;\\s]+")) {
            String hex = crudo.strip().replace("#", "");
            if (!hex.matches("[0-9A-Fa-f]{6}")) continue;
            double r = Integer.parseInt(hex.substring(0, 2), 16) / 255.0;
            double g = Integer.parseInt(hex.substring(2, 4), 16) / 255.0;
            double b = Integer.parseInt(hex.substring(4, 6), 16) / 255.0;
            double luz = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            double max = Math.max(r, Math.max(g, b));
            double vivo = max == 0 ? 0 : (max - Math.min(r, Math.min(g, b))) / max;
            if (luz >= 0.50 && luz <= 0.85 && vivo >= 0.35 && vivo > masVivo) {
                masVivo = vivo;
                mejor = "#" + hex.toUpperCase(Locale.ROOT);
            }
        }
        return mejor == null ? AMARILLO : mejor;
    }

    /** "&H00BBGGRR&" → "#RRGGBB". */
    private static String colorDe(String ass) {
        return "#" + ass.substring(8, 10) + ass.substring(6, 8) + ass.substring(4, 6);
    }

    /** Qué tan claro es un color, de 0 (negro) a 1 (blanco). */
    static double luz(String hex) {
        String h = hex.replace("#", "");
        return (0.2126 * Integer.parseInt(h.substring(0, 2), 16) + 0.7152 * Integer.parseInt(h.substring(2, 4), 16)
                + 0.0722 * Integer.parseInt(h.substring(4, 6), 16)) / 255.0;
    }

    /** "#RRGGBB" → "&H00BBGGRR&": ASS escribe los colores al revés. */
    static String colorAss(String hex) {
        String h = hex.replace("#", "");
        return "&H00" + h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2) + "&";
    }
}

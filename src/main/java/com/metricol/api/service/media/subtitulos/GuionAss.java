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
 */
public final class GuionAss {

    /** Lo que dura en pantalla la última palabra de un trozo después de dicha. */
    static final double COLA = 0.3;

    /** El color de resaltado cuando la marca no trae uno que se lea sobre video. */
    static final String AMARILLO = "#FFD400";

    private GuionAss() {
    }

    /**
     * @param color el color de la marca para resaltar ("#1A3A6B"); si es muy
     *              oscuro para leerse encima de un video, se usa amarillo
     */
    public static String escribir(List<Palabra> palabras, EstiloSubtitulos estilo, int ancho, int alto,
            String color) {
        boolean vertical = alto > ancho;
        int base = Math.min(ancho, alto);
        String resalte = colorAss(resalteLegible(color));
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\nScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\n")
                .append("PlayResX: ").append(ancho).append("\nPlayResY: ").append(alto).append("\n\n");
        ass.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, ")
                .append("BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, ")
                .append("Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        int margenLados = (int) Math.round(ancho * 0.08);
        switch (estilo) {
            case PALABRA -> {
                int tam = (int) Math.round(base * 0.075);
                estilo(ass, "Montserrat ExtraBold", tam, "&H00FFFFFF", "&H00000000", "&H64000000", 1,
                        Math.max(2, tam * 0.10), Math.max(1, tam * 0.04), margenLados,
                        (int) Math.round(alto * (vertical ? 0.30 : 0.12)));
            }
            case REBOTE -> {
                int tam = (int) Math.round(base * 0.11);
                estilo(ass, "Montserrat Black", tam, "&H00FFFFFF", "&H00000000", "&H64000000", 1,
                        Math.max(3, tam * 0.11), Math.max(1, tam * 0.05), margenLados,
                        (int) Math.round(alto * (vertical ? 0.32 : 0.14)));
            }
            case CLASICO -> {
                int tam = (int) Math.round(base * 0.05);
                // BorderStyle 3: una franja detrás del texto, semitransparente.
                estilo(ass, "Montserrat ExtraBold", tam, "&H00FFFFFF", "&H8C000000", "&H8C000000", 3,
                        Math.max(4, tam * 0.30), 0, margenLados,
                        (int) Math.round(alto * (vertical ? 0.20 : 0.07)));
            }
        }
        ass.append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        List<List<Palabra>> trozos = trozos(palabras, estilo);
        for (int t = 0; t < trozos.size(); t++) {
            List<Palabra> trozo = trozos.get(t);
            double siguiente = t + 1 < trozos.size() ? trozos.get(t + 1).get(0).inicio() : Double.MAX_VALUE;
            double finTrozo = Math.min(trozo.get(trozo.size() - 1).fin() + COLA, siguiente);
            switch (estilo) {
                case PALABRA -> {
                    // Un evento por palabra: el trozo entero, con la que suena resaltada.
                    for (int i = 0; i < trozo.size(); i++) {
                        double desde = trozo.get(i).inicio();
                        double hasta = i + 1 < trozo.size() ? trozo.get(i + 1).inicio() : finTrozo;
                        if (hasta - desde < 0.01) continue;
                        StringBuilder texto = new StringBuilder();
                        for (int j = 0; j < trozo.size(); j++) {
                            if (j > 0) texto.append(' ');
                            String palabra = limpio(trozo.get(j).texto());
                            texto.append(j == i
                                    ? "{\\c" + resalte + "\\fscx112\\fscy112}" + palabra + "{\\r}"
                                    : palabra);
                        }
                        evento(ass, desde, hasta, texto.toString());
                    }
                }
                case REBOTE -> {
                    StringBuilder texto = new StringBuilder(
                            "{\\fscx70\\fscy70\\t(0,90,\\fscx110\\fscy110)\\t(90,170,\\fscx100\\fscy100)}");
                    for (int j = 0; j < trozo.size(); j++) {
                        if (j > 0) texto.append(' ');
                        String palabra = limpio(trozo.get(j).texto()).toUpperCase(Locale.forLanguageTag("es"));
                        // Cada tercer trozo, en el color de la marca: da ritmo sin cansar.
                        texto.append(t % 3 == 2 ? "{\\c" + resalte + "}" + palabra + "{\\c&H00FFFFFF&}" : palabra);
                    }
                    evento(ass, trozo.get(0).inicio(), finTrozo, texto.toString());
                }
                case CLASICO -> {
                    StringBuilder texto = new StringBuilder();
                    for (int j = 0; j < trozo.size(); j++) {
                        if (j > 0) texto.append(' ');
                        texto.append(limpio(trozo.get(j).texto()));
                    }
                    evento(ass, trozo.get(0).inicio(), finTrozo, texto.toString());
                }
            }
        }
        return ass.toString();
    }

    /** Cuántas palabras caben a la vez en cada estilo; una pausa siempre corta. */
    static List<List<Palabra>> trozos(List<Palabra> palabras, EstiloSubtitulos estilo) {
        int maxPalabras = switch (estilo) {
            case PALABRA -> 3;
            case REBOTE -> 2;
            case CLASICO -> 7;
        };
        int maxLetras = switch (estilo) {
            case PALABRA -> 18;
            case REBOTE -> 14;
            case CLASICO -> 40;
        };
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

    private static void estilo(StringBuilder ass, String letra, int tam, String texto, String contorno,
            String sombra, int borde, double grosor, double profundidad, int lados, int abajo) {
        ass.append(String.format(Locale.US,
                "Style: Default,%s,%d,%s,%s,%s,%s,0,0,0,0,100,100,0,0,%d,%.1f,%.1f,2,%d,%d,%d,1%n",
                letra, tam, texto, texto, contorno, sombra, borde, grosor, profundidad, lados, lados, abajo));
    }

    private static void evento(StringBuilder ass, double desde, double hasta, String texto) {
        ass.append("Dialogue: 0,").append(tiempo(desde)).append(',').append(tiempo(hasta))
                .append(",Default,,0,0,0,,").append(texto).append('\n');
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

    /** "#RRGGBB" → "&H00BBGGRR&": ASS escribe los colores al revés. */
    static String colorAss(String hex) {
        String h = hex.replace("#", "");
        return "&H00" + h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2) + "&";
    }
}

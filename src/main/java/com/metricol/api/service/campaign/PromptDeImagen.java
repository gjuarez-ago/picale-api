package com.metricol.api.service.campaign;

import java.util.List;
import java.util.Locale;

import com.metricol.api.service.campaign.SelloDeLogo.Posicion;

/**
 * El prompt de imagen de una campaña, armado con reglas fijas.
 *
 * <p>Lo que decide qué se dice —la escena, los textos, cuál foto manda— lo
 * pone el director de arte; lo que no debe variar nunca —márgenes, tipografía,
 * dónde queda el espacio del logo, qué NO dibujar— vive aquí, escrito una sola
 * vez. Así cada campaña sigue las mismas reglas de oficio y lo único que
 * cambia es el contenido.
 *
 * <p>Los márgenes van en píxeles del lienzo de {@code gpt-image} (1024 × 1536)
 * y no en porcentajes: con "deja un 12 % libre" el modelo ponía el logo pegado
 * al borde; con un rectángulo concreto no queda nada que interpretar.
 */
final class PromptDeImagen {

    private PromptDeImagen() {
    }

    /** Las composiciones que el director puede pedir. Los códigos son los de {@code ArtDirector.LAYOUTS}. */
    enum Layout {
        // Al pasar de 2:3 a 4:5 se quitan 256 px. Se quitan de donde SOBRA: con el texto abajo
        // (banda inferior) casi todo sale de la foto de arriba, y al revés. Cortar parejo era lo
        // que dejaba el botón a medias contra el borde de abajo.
        PHOTO_BOTTOM_BAND("photo_bottom_band", 0.8),
        PHOTO_TOP_TITLE("photo_top_title", 0.2),
        FRAMED_PHOTO("framed_photo", 0.5);

        final String codigo;

        /** Qué parte del recorte vertical se toma de arriba (0 = todo de abajo, 1 = todo de arriba). */
        final double cortaArriba;

        Layout(String codigo, double cortaArriba) {
            this.codigo = codigo;
            this.cortaArriba = cortaArriba;
        }

        static Layout de(String codigo) {
            String limpio = codigo == null ? "" : codigo.trim().toLowerCase(Locale.ROOT);
            for (Layout layout : values()) {
                if (layout.codigo.equals(limpio)) {
                    return layout;
                }
            }
            return PHOTO_BOTTOM_BAND;
        }
    }

    /**
     * @param fotos cuántas fotos de referencia se mandan; la protagonista va PRIMERA
     * @param logo dónde se pegará el logo real, o {@code null} si no hay logo
     */
    record Datos(
            Lienzo lienzo,
            Layout layout,
            String negocio,
            String escena,
            String titular,
            String subtitulo,
            String cta,
            List<String> paleta,
            int fotos,
            Posicion logo) {
    }

    static String armar(Datos d) {
        StringBuilder t = new StringBuilder();
        t.append("Create ONE finished, professional social-media advertisement for ")
                .append(valor(d.negocio(), "a local business"))
                .append(d.lienzo() == Lienzo.CUADRADO
                        ? ". Square canvas of 1024x1024 pixels.\n\n"
                        : ". Portrait canvas of 1024x1536 pixels.\n\n");

        t.append("SAFE AREA (critical): ").append(zonaSegura(d.lienzo(), d.layout())).append("\n\n");

        if (d.fotos() > 0) {
            t.append("PHOTO: Photo 1 is the hero and must appear large, complete and recognizable. ");
            if (d.fotos() > 1) {
                t.append("The other photos are context only: do not make a collage or extra panels. ");
            }
            t.append("These are REAL photographs of the business: keep the real people, equipment, place and colors ")
                    .append("faithful. Do not redraw, restyle or replace anything in them; only scale and place them ")
                    .append("as the layout describes. Never add fog, haze, blur or glow effects over the photo.\n\n");
        } else {
            t.append("PHOTO: No reference photo is given. Create a realistic, credible photographic scene. ")
                    .append("Never add fog, haze or glow effects.\n\n");
        }

        // Sin botón no se nombra el botón en ningún lado: un prompt que lo menciona lo dibuja.
        boolean conBoton = hay(d.cta());
        t.append("LAYOUT: ").append(descripcion(d.layout(), d.lienzo(), conBoton)).append("\n\n");

        t.append("TEXT: render exactly these words, in Spanish, letter for letter and with their accents, ")
                .append("and NO other words anywhere in the image.\n");
        t.append("  HEADLINE: \"").append(d.titular()).append("\"\n");
        if (hay(d.subtitulo())) {
            t.append("  SUBTITLE: \"").append(d.subtitulo()).append("\"\n");
        }
        if (conBoton) {
            t.append("  BUTTON: \"").append(d.cta()).append("\" (a pill-shaped button with the text only, no icon)\n");
        } else {
            t.append("There is NO button and NO call to action in this image: do not draw any button, pill, arrow ")
                    .append("or \"contact us\" text. The call to action goes in the post's caption, not here.\n");
        }
        t.append("Typography: modern, bold, geometric sans-serif. The headline is the largest element (at most two ")
                .append("short lines), the subtitle about 40% of its size. High contrast against whatever is behind each ")
                .append("text (white on dark, dark on light). One consistent alignment. Easy to read on a phone, but ")
                .append("RESTRAINED: all the text together").append(conBoton ? ", button included," : "")
                .append(" takes no more than about a quarter of the image height, so the photo stays the protagonist.")
                .append(conBoton
                        ? " The BUTTON is a compact pill, no wider than about half of the text block, never a "
                                + "full-width bar."
                        : "")
                .append(" Approximate sizes: ")
                .append(d.lienzo() == Lienzo.CUADRADO
                        ? "headline lines about 60 px tall, subtitle about 32 px" + (conBoton ? ", button about 68 px tall.\n\n" : ".\n\n")
                        : "headline lines about 76 px tall, subtitle about 40 px" + (conBoton ? ", button about 88 px tall.\n\n" : ".\n\n"));

        if (d.paleta() != null && !d.paleta().isEmpty()) {
            t.append("COLORS: use exactly these brand colors, read from the logo, for panels, frames, the button and ")
                    .append("accents, and no other accent colors: ").append(String.join(", ", d.paleta())).append(".\n\n");
        } else {
            t.append("COLORS: a restrained professional palette (deep navy, white and one accent taken from the ")
                    .append("photo).\n\n");
        }

        if (d.logo() != null) {
            t.append("LOGO: leave ").append(zonaLogo(d.logo(), d.lienzo(), d.layout()))
                    .append(" completely clean (plain background, no text, no key subject): the business's real logo ")
                    .append("will be placed there afterwards. Do not draw any logo, emblem or brand mark anywhere.\n\n");
        } else {
            t.append("LOGO: do not draw any logo, emblem or brand mark anywhere.\n\n");
        }

        String escena = hay(d.escena())
                ? d.escena()
                : "Natural daylight, true-to-life colors, clean and credible, no stylization.";
        t.append("SCENE DIRECTION: ").append(escena).append("\n\n");

        t.append("AVOID: watermarks, fake interface elements, extra logos, icons on the button (no clock, no phone), ")
                .append("placeholder text, any word not listed above, misspellings, cut-off letters, text touching ")
                .append("an edge, overlapping text.");
        return t.toString();
    }

    /**
     * Cuántos píxeles se cortan arriba y abajo del lienzo de 1536 px al pasar a 4:5 ({@code [arriba, abajo]}).
     * Los demás lienzos no cortan a lo alto.
     */
    static int[] cortesVerticales(Lienzo lienzo, Layout layout) {
        if (lienzo != Lienzo.CUATRO_QUINTOS) {
            return new int[] {0, 0};
        }
        int total = 1536 - 1280;
        int arriba = (int) Math.round(total * layout.cortaArriba);
        return new int[] {arriba, total - arriba};
    }

    /** Sin layout: recorte parejo, que es como se corta un carrusel. */
    static String zonaSegura(Lienzo lienzo) {
        return zonaSegura(lienzo, Layout.FRAMED_PHOTO);
    }

    /** Lo que sobrevive al recorte y a la interfaz de la red, en píxeles del lienzo pedido. */
    static String zonaSegura(Lienzo lienzo, Layout layout) {
        int[] cortes = cortesVerticales(lienzo, layout);
        int yMin = cortes[0] + 72;
        int yMax = 1536 - cortes[1] - 72;
        return switch (lienzo) {
            case HISTORIA -> "The image is cut to 9:16 and shown under the app's own interface. Every letter, "
                    + "button, face and key object must lie completely inside the rectangle x=130..894, "
                    + "y=240..1230; the margins are cut off or covered. Plain background color may run past that "
                    + "rectangle to the edges, but nothing that must be read or seen.";
            case CUADRADO -> "The image is a 1:1 square shown as it is. Every letter, button, face and key object "
                    + "must lie completely inside the rectangle x=80..944, y=80..944. A panel or frame may run to "
                    + "the edges only as plain color, and never let any text touch an edge.";
            case CUATRO_QUINTOS -> "The image is cut to 4:5: the top " + cortes[0] + " and the bottom "
                    + cortes[1] + " pixels are cut. Every letter, button, face and key object must lie completely "
                    + "inside the rectangle x=64..960, y=" + yMin + ".." + yMax + ". A panel or frame may run "
                    + "to the edges only as plain color, and never let any text touch an edge.";
        };
    }

    private static String descripcion(Layout layout, Lienzo lienzo, boolean conBoton) {
        // Sin botón la banda es aún más baja: solo lleva titular y subtítulo.
        int alturaBanda = lienzo == Lienzo.CUADRADO ? (conBoton ? 780 : 830) : (conBoton ? 1150 : 1210);
        return switch (layout) {
            case PHOTO_BOTTOM_BAND -> "The hero photo fills the whole canvas, unchanged, and stays the "
                    + "protagonist: the panel covers only about a quarter of the image. A solid panel in the "
                    + "primary brand color, with softly rounded top corners, is anchored to the bottom and rises only "
                    + "to about y=" + alturaBanda + ". Inside the safe rectangle, left-aligned on the panel: a short "
                    + (conBoton
                            ? "HEADLINE, the SUBTITLE under it, and a compact BUTTON below, with comfortable empty "
                                    + "space under the button."
                            : "HEADLINE and the SUBTITLE under it, with comfortable empty space below them.");
            case PHOTO_TOP_TITLE -> "The hero photo fills the whole canvas, unchanged, with only a soft dark "
                    + "gradient over the top third to hold the text (no fog, no blur). Inside the safe rectangle, "
                    + "below the logo area: the HEADLINE, then the SUBTITLE."
                    + (conBoton ? " The BUTTON sits near the bottom of the safe rectangle."
                            : " Nothing else is written on the photo: its lower part stays completely clean.");
            case FRAMED_PHOTO -> "A solid background in the primary brand color fills the canvas. The hero photo "
                    + "sits in the middle inside a large rounded rectangle (its content unchanged and uncropped as "
                    + "much as possible). The HEADLINE, in white, goes above the photo; "
                    + (conBoton ? "the SUBTITLE and the BUTTON go below it, all on the plain color."
                            : "the SUBTITLE goes below it, on the plain color.");
        };
    }

    /** Sin layout: recorte parejo. */
    static String zonaLogo(Posicion posicion, Lienzo lienzo) {
        return zonaLogo(posicion, lienzo, Layout.FRAMED_PHOTO);
    }

    /** El rincón que se deja libre para el logo, en píxeles aproximados del lienzo. */
    static String zonaLogo(Posicion posicion, Lienzo lienzo, Layout layout) {
        int[] cortes = cortesVerticales(lienzo, layout);
        int ancho = 440;
        int alto = 210;
        int x;
        int y;
        switch (lienzo) {
            case HISTORIA -> {
                x = posicion.izquierda() ? 110 : posicion.derecha() ? 470 : 292;
                y = posicion.arriba() ? 215 : 1035;
            }
            case CUADRADO -> {
                x = posicion.izquierda() ? 40 : posicion.derecha() ? 544 : 292;
                y = posicion.arriba() ? 40 : 774;
            }
            default -> {
                x = posicion.izquierda() ? 40 : posicion.derecha() ? 550 : 292;
                // El logo se pega a 37 px del borde de ARRIBA (o a 48 del de abajo) de la imagen ya
                // recortada; en el lienzo de 1536 eso cae después del corte de cada lado.
                y = posicion.arriba() ? cortes[0] + 37 : 1536 - cortes[1] - 48 - alto;
            }
        }
        return "the area of about " + ancho + "x" + alto + " pixels " + (posicion.arriba() ? "at the top" : "at the bottom")
                + "-" + (posicion.izquierda() ? "left" : posicion.derecha() ? "right" : "center")
                + " (roughly x=" + x + ".." + (x + ancho) + ", y=" + y + ".." + (y + alto) + ")";
    }

    private static boolean hay(String texto) {
        return texto != null && !texto.isBlank();
    }

    private static String valor(String texto, String porDefecto) {
        return hay(texto) ? texto.trim() : porDefecto;
    }
}

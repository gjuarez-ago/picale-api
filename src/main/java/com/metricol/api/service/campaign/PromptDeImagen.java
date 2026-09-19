package com.metricol.api.service.campaign;

import java.util.List;
import java.util.Locale;

import com.metricol.api.service.campaign.CampaignImageService.Formato;
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
        PHOTO_BOTTOM_BAND("photo_bottom_band"),
        PHOTO_TOP_TITLE("photo_top_title"),
        FRAMED_PHOTO("framed_photo");

        final String codigo;

        Layout(String codigo) {
            this.codigo = codigo;
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
            Formato formato,
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
                .append(". Portrait canvas of 1024x1536 pixels.\n\n");

        t.append("SAFE AREA (critical): ").append(zonaSegura(d.formato())).append("\n\n");

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

        t.append("LAYOUT: ").append(descripcion(d.layout())).append("\n\n");

        t.append("TEXT: render exactly these words, in Spanish, letter for letter and with their accents, ")
                .append("and NO other words anywhere in the image.\n");
        t.append("  HEADLINE: \"").append(d.titular()).append("\"\n");
        if (hay(d.subtitulo())) {
            t.append("  SUBTITLE: \"").append(d.subtitulo()).append("\"\n");
        }
        if (hay(d.cta())) {
            t.append("  BUTTON: \"").append(d.cta()).append("\" (a pill-shaped button with the text only, no icon)\n");
        }
        t.append("Typography: modern, bold, geometric sans-serif. The headline is the largest element (at most three ")
                .append("lines), the subtitle about 40% of its size. High contrast against whatever is behind each ")
                .append("text (white on dark, dark on light). One consistent alignment. All text large and easy to ")
                .append("read on a phone; no small print.\n\n");

        if (d.paleta() != null && !d.paleta().isEmpty()) {
            t.append("COLORS: use exactly these brand colors, read from the logo, for panels, frames, the button and ")
                    .append("accents, and no other accent colors: ").append(String.join(", ", d.paleta())).append(".\n\n");
        } else {
            t.append("COLORS: a restrained professional palette (deep navy, white and one accent taken from the ")
                    .append("photo).\n\n");
        }

        if (d.logo() != null) {
            t.append("LOGO: leave ").append(zonaLogo(d.logo(), d.formato()))
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

    /** Lo que sobrevive al recorte y a la interfaz de la red, en píxeles del lienzo de 1024x1536. */
    static String zonaSegura(Formato formato) {
        return switch (formato) {
            case STORY -> "The image is cut to 9:16 and shown under the app's own interface. Every letter, button, "
                    + "face and key object must lie completely inside the rectangle x=130..894, y=240..1230; the "
                    + "margins are cut off or covered. Plain background color may run past that rectangle to the "
                    + "edges, but nothing that must be read or seen.";
            default -> "The image is cut to 4:5, removing the top and bottom strips. Every letter, button, face and "
                    + "key object must lie completely inside the rectangle x=64..960, y=200..1336; the top 200 and "
                    + "bottom 200 pixels are cut or covered. A panel or frame may run to the edges only as plain "
                    + "color, and never let any text touch an edge.";
        };
    }

    private static String descripcion(Layout layout) {
        return switch (layout) {
            case PHOTO_BOTTOM_BAND -> "The hero photo fills the whole canvas, unchanged. A solid panel in the "
                    + "primary brand color, with softly rounded top corners, is anchored to the bottom and rises to "
                    + "about y=1000. Inside the safe rectangle, left-aligned on the panel: the HEADLINE, the "
                    + "SUBTITLE under it, and the BUTTON at the bottom of the panel.";
            case PHOTO_TOP_TITLE -> "The hero photo fills the whole canvas, unchanged, with only a soft dark "
                    + "gradient over the top third to hold the text (no fog, no blur). Inside the safe rectangle, "
                    + "below the logo area: the HEADLINE, then the SUBTITLE. The BUTTON sits near the bottom of the "
                    + "safe rectangle.";
            case FRAMED_PHOTO -> "A solid background in the primary brand color fills the canvas. The hero photo "
                    + "sits in the middle inside a large rounded rectangle (its content unchanged and uncropped as "
                    + "much as possible). The HEADLINE, in white, goes above the photo; the SUBTITLE and the BUTTON "
                    + "go below it, all on the plain color.";
        };
    }

    /** El rincón que se deja libre para el logo, en píxeles aproximados del lienzo. */
    static String zonaLogo(Posicion posicion, Formato formato) {
        boolean historia = formato == Formato.STORY;
        int ancho = 440;
        int alto = 210;
        int x = posicion.izquierda() ? (historia ? 110 : 40) : posicion.derecha() ? (historia ? 470 : 550) : 292;
        int y = posicion.arriba() ? (historia ? 215 : 165) : (historia ? 1035 : 1150);
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

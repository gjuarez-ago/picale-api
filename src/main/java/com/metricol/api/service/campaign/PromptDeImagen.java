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
 * al borde; con un rectángulo concreto no queda nada que interpretar. Por lo
 * mismo, con el título arriba se le dice en qué píxel empieza el titular:
 * "debajo del área del logo" lo dejaba unos 60 px dentro de esa área, y el
 * logo real caía encima de la primera letra.
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
        FRAMED_PHOTO("framed_photo", 0.5),
        // Las tres de abajo existen porque con solo las de arriba todas las imágenes salían con la
        // misma cara de plantilla —panel, marco, panel— y la gente lo notó (oct 2026).
        FULL_BLEED("full_bleed", 0.5),
        STICKER_LABEL("sticker_label", 0.5),
        TYPE_POSTER("type_poster", 0.8);

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

    /** Un rectángulo del lienzo, en píxeles. */
    record Zona(int x, int y, int ancho, int alto) {
        int derecha() {
            return x + ancho;
        }

        int abajo() {
            return y + alto;
        }
    }

    static String armar(Datos d) {
        return armar(d, false);
    }

    static String armar(Datos d, boolean logoIntegrado) {
        return armar(d, logoIntegrado, false);
    }

    static String armar(Datos d, boolean logoIntegrado, boolean conChispa) {
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
            // Antes: "no la redibujes, solo escálala y acomódala". El resultado era la misma foto
            // con un texto encima, no un anuncio: la gente lo comparó con lo que logra ChatGPT con
            // la misma foto (oct 2026). Ahora la foto es la REFERENCIA de una edición completa, y lo
            // que no se toca es lo que la hace real.
            t.append("These are REAL photographs of the business and Photo 1 is the reference for a COMPLETE ")
                    .append("professional edit, the way a senior designer and photo retoucher would finish an ad: ")
                    .append("reframe and crop for impact, extend the background naturally where the layout needs room, ")
                    .append("relight, color-grade, sharpen, remove small distractions (trash, cables, stray objects), ")
                    .append("add depth and a premium finish. It must look clearly better than the original photo. ")
                    .append("What makes it real stays faithful and recognizable: the same dishes, products, portions, ")
                    .append("people, faces, place and materials. Never swap the food or products, never invent ")
                    .append("people, never change faces. Never add fog, haze, heavy blur or glow over the subject.\n\n");
        } else if (conChispa) {
            t.append("PHOTO: No real-subject photograph. Use the approved CHISPA_IDENTITY reference as a stylized character, ")
                    .append("preserving its face, proportions and colors. No unreferenced real people.\n\n");
        } else {
            // Sin foto real no se inventa una persona: una cara hecha por la IA
            // presentada como del negocio resta credibilidad (6 oct 2026, un
            // diseño del Día de Muertos salió con una mujer inventada).
            // Antes: "arma la escena con objetos, texturas, ilustración o tipografía". Con eso
            // una repostería recibió olas abstractas y un titular: nada de lo que vende (oct
            // 2026). Sin foto, la protagonista es una foto publicitaria de SU producto.
            t.append("PHOTO: No reference photo is given, so CREATE the hero image: a stunning, photorealistic ")
                    .append("commercial photo of what this business actually sells or does, as described in SCENE ")
                    .append("DIRECTION (its dishes, desserts, products, finished work or place), shot like a high-end ")
                    .append("advertising campaign: irresistible, real textures, professional lighting, depth of field, ")
                    .append("a styled set that matches the brand colors. It is the protagonist wherever the layout ")
                    .append("says hero photo, never an abstract pattern or empty background. NO PEOPLE AT ALL: no ")
                    .append("faces, no bodies, no hands, no silhouettes. Never add fog, haze or glow effects.\n\n");
        }

        // Sin botón no se nombra el botón en ningún lado: un prompt que lo menciona lo dibuja.
        boolean conBoton = hay(d.cta());
        t.append("LAYOUT: ").append(descripcion(d.layout(), d.lienzo(), conBoton, d.logo())).append("\n\n");

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
        boolean poster = d.layout() == Layout.TYPE_POSTER;
        t.append(poster
                        ? "Typography: an expressive, heavy display typeface with character (condensed or rounded, "
                                + "matching the mood of the business), tight leading. The headline is the hero of the "
                                + "image (at most three short lines) and the subtitle about 30% of its size. "
                        : "Typography: modern, bold, geometric sans-serif. The headline is the largest element (at "
                                + "most two short lines), the subtitle about 40% of its size. ")
                .append("High contrast against whatever is behind each ")
                .append("text (white on dark, dark on light). One consistent alignment. Easy to read on a phone, ")
                .append(poster
                        ? "and BIG: the text block may take up to about 40% of the image height."
                        : "but RESTRAINED: all the text together" + (conBoton ? ", button included," : "")
                                + " takes no more than about a quarter of the image height, so the photo stays the "
                                + "protagonist.")
                .append(conBoton
                        ? " The BUTTON is a compact pill, no wider than about half of the text block, never a "
                                + "full-width bar."
                        : "")
                .append(" Approximate sizes: ")
                .append(poster
                        ? (d.lienzo() == Lienzo.CUADRADO
                                ? "headline lines about 110 px tall, subtitle about 34 px"
                                : "headline lines about 140 px tall, subtitle about 42 px")
                                + (conBoton ? ", button about 80 px tall.\n\n" : ".\n\n")
                        : d.lienzo() == Lienzo.CUADRADO
                        ? "headline lines about 60 px tall, subtitle about 32 px" + (conBoton ? ", button about 68 px tall.\n\n" : ".\n\n")
                        : "headline lines about 76 px tall, subtitle about 40 px" + (conBoton ? ", button about 88 px tall.\n\n" : ".\n\n"));

        if (d.paleta() != null && !d.paleta().isEmpty()) {
            t.append("COLORS: use exactly these brand colors, read from the logo, for panels, frames, the button and ")
                    .append("accents, and no other accent colors: ").append(String.join(", ", d.paleta())).append(".\n\n");
        } else {
            t.append("COLORS: a restrained professional palette (deep navy, white and one accent taken from the ")
                    .append("photo).\n\n");
        }

        if (logoIntegrado) {
            t.append("LOGO: integrate exactly ONE instance of the provided logo reference into the composition. ")
                    .append("Its reference role and allowed transformations are defined in the creative specification. ")
                    .append("The logo's original lettering is allowed in addition to the requested campaign text.\n\n");
        } else if (d.logo() != null) {
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

    /** Donde termina abajo la zona segura del lienzo, en píxeles: lo de más abajo se corta o se tapa. */
    static int abajoSeguro(Lienzo lienzo, Layout layout) {
        return switch (lienzo) {
            case HISTORIA -> 1230;
            case CUADRADO -> 944;
            case CUATRO_QUINTOS -> 1536 - cortesVerticales(lienzo, layout)[1] - 72;
        };
    }

    /** Donde empieza a lo alto la zona segura del lienzo, en píxeles. */
    private static int arribaSeguro(Lienzo lienzo, Layout layout) {
        return switch (lienzo) {
            case HISTORIA -> 240;
            case CUADRADO -> 80;
            case CUATRO_QUINTOS -> cortesVerticales(lienzo, layout)[0] + 72;
        };
    }

    /**
     * En qué píxel empieza el titular cuando el texto va arriba.
     *
     * <p>Con el logo arriba, debajo de su rincón y con aire; si no, al borde de
     * la zona segura. Es un número y no una relación ("debajo del logo") porque
     * la relación el modelo la cumplía a ojo, unos 60 px cortos, y el logo real
     * caía sobre la primera letra del titular.
     */
    static int inicioDelTitulo(Lienzo lienzo, Layout layout, Posicion logo) {
        int aire = 40;
        if (logo == null || !logo.arriba()) {
            return arribaSeguro(lienzo, layout) + aire;
        }
        return rectanguloLogo(logo, lienzo, layout).abajo() + aire;
    }

    private static String descripcion(Layout layout, Lienzo lienzo, boolean conBoton, Posicion logo) {
        // Sin botón la banda es aún más baja: solo lleva titular y subtítulo.
        int alturaBanda = lienzo == Lienzo.CUADRADO ? (conBoton ? 780 : 830) : (conBoton ? 1150 : 1210);
        return switch (layout) {
            case PHOTO_BOTTOM_BAND -> "The hero photo fills the whole canvas, edited as described in PHOTO, and stays the "
                    + "protagonist: the panel covers only about a quarter of the image. A solid panel in the "
                    + "primary brand color, with softly rounded top corners, is anchored to the bottom and rises only "
                    + "to about y=" + alturaBanda + ". Inside the safe rectangle, left-aligned on the panel: a short "
                    + (conBoton
                            ? "HEADLINE, the SUBTITLE under it, and a compact BUTTON below, with comfortable empty "
                                    + "space under the button."
                            : "HEADLINE and the SUBTITLE under it, with comfortable empty space below them.");
            case PHOTO_TOP_TITLE -> {
                int desde = inicioDelTitulo(lienzo, layout, logo);
                yield "The hero photo fills the whole canvas, edited as described in PHOTO, with only a soft dark gradient over the "
                        + "top third to hold the text (no fog, no blur). Inside the safe rectangle, left-aligned: the "
                        + "HEADLINE, whose top edge is at about y=" + desde + " and never higher, then the SUBTITLE "
                        + "right under it."
                        + (logo != null && logo.arriba()
                                ? " Above y=" + desde + " there is only the plain gradient: that band is reserved "
                                        + "for the logo."
                                : "")
                        + (conBoton ? " The BUTTON sits near the bottom of the safe rectangle, left-aligned as well."
                                : " Nothing else is written on the photo: its lower part stays completely clean.");
            }
            case FRAMED_PHOTO -> "A solid background in the primary brand color fills the canvas. The hero photo "
                    + "sits in the middle inside a large rounded rectangle (edited as described in PHOTO, its subject complete as "
                    + "much as possible). The HEADLINE, in white, goes above the photo; "
                    + (conBoton ? "the SUBTITLE and the BUTTON go below it, all on the plain color."
                            : "the SUBTITLE goes below it, on the plain color.");
            case FULL_BLEED -> "Editorial, like a magazine photo: the hero photo fills the whole canvas edge to "
                    + "edge, edited as described in PHOTO, with NO panel, NO box, NO frame and NO band anywhere. The HEADLINE is set "
                    + "directly on the calmest, darkest (or lightest) area of the photo in the lower part of the safe "
                    + "rectangle, left-aligned, with only a very soft local shadow behind the letters for legibility; "
                    + "the SUBTITLE right under it in the same alignment. Every line of text, complete, ends ABOVE y="
                    + (abajoSeguro(lienzo, layout) - 40) + ": nothing written below that line, it gets cut."
                    + (conBoton ? " The BUTTON follows the text, small, in the primary brand color." : "")
                    + " Generous empty space around the text: the photo breathes.";
            case STICKER_LABEL -> "Playful and hand-placed: the hero photo fills the whole canvas edge to edge, "
                    + "edited as described in PHOTO. The HEADLINE sits inside one or two label shapes in the primary brand color, like "
                    + "stickers or paper tags stuck on the photo, slightly tilted (about 3 to 5 degrees, the two "
                    + "labels in opposite directions), with a thin offset shadow, placed over the lower-middle part "
                    + "of the safe rectangle and never covering the main subject's face or the product itself; the "
                    + "lowest label ends ABOVE y=" + (abajoSeguro(lienzo, layout) - 40) + ". "
                    + (conBoton ? "The SUBTITLE and the BUTTON go on a smaller label in the secondary brand color "
                                    + "under it."
                            : "The SUBTITLE, if any, on a smaller label in the secondary brand color under it.")
                    + " No full-width bars, no rounded-rectangle frame.";
            case TYPE_POSTER -> {
                int desde = inicioDelTitulo(lienzo, layout, logo);
                yield "A bold typographic poster. The upper part of the canvas is plain primary brand color and "
                        + "holds a HUGE HEADLINE, left-aligned, whose top edge is at about y=" + desde + " and never "
                        + "higher; the SUBTITLE under it. The hero photo fills the lower part of the canvas edge to "
                        + "edge, edited as described in PHOTO, and meets the color with an organic, slightly curved edge (never a "
                        + "straight line, never a rounded rectangle); the last line of the headline may overlap the "
                        + "top of the photo a little, for depth."
                        + (logo != null && logo.arriba()
                                ? " Above y=" + desde + " there is only plain color: that band is reserved for the "
                                        + "logo."
                                : "")
                        + (conBoton ? " The BUTTON sits on the photo near the bottom of the safe rectangle." : "");
            }
        };
    }

    /** Sin layout: recorte parejo. */
    static String zonaLogo(Posicion posicion, Lienzo lienzo) {
        return zonaLogo(posicion, lienzo, Layout.FRAMED_PHOTO);
    }

    /** El rincón que se deja libre para el logo, en píxeles aproximados del lienzo. */
    static String zonaLogo(Posicion posicion, Lienzo lienzo, Layout layout) {
        Zona z = rectanguloLogo(posicion, lienzo, layout);
        return "the area of about " + z.ancho() + "x" + z.alto() + " pixels " + (posicion.arriba() ? "at the top" : "at the bottom")
                + "-" + (posicion.izquierda() ? "left" : posicion.derecha() ? "right" : "center")
                + " (roughly x=" + z.x() + ".." + z.derecha() + ", y=" + z.y() + ".." + z.abajo() + ")";
    }

    /** El rectángulo del logo en el lienzo. Lo que {@link #zonaLogo} pone en palabras. */
    static Zona rectanguloLogo(Posicion posicion, Lienzo lienzo, Layout layout) {
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
        return new Zona(x, y, ancho, alto);
    }

    private static boolean hay(String texto) {
        return texto != null && !texto.isBlank();
    }

    private static String valor(String texto, String porDefecto) {
        return hay(texto) ? texto.trim() : porDefecto;
    }
}

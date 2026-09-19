package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.campaign.PromptDeImagen.Datos;
import com.metricol.api.service.campaign.PromptDeImagen.Layout;
import com.metricol.api.service.campaign.SelloDeLogo.Posicion;

class PromptDeImagenTest {

    private static Datos datos(Lienzo formato, Layout layout, int fotos, Posicion logo) {
        return new Datos(formato, layout, "CMRG S.A. de C.V.", "Warm afternoon light.",
                "Montaje seguro y a tiempo", "Manzanillo, Colima", "Escríbenos por WhatsApp",
                List.of("#0B2A5B", "#1FA34A"), fotos, logo);
    }

    @Test
    @DisplayName("los textos van literales y entre comillas: la IA los copia, no los inventa")
    void textosLiterales() {
        String prompt = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, Posicion.TOP_LEFT));

        assertThat(prompt)
                .contains("HEADLINE: \"Montaje seguro y a tiempo\"")
                .contains("SUBTITLE: \"Manzanillo, Colima\"")
                .contains("BUTTON: \"Escríbenos por WhatsApp\"")
                .contains("letter for letter")
                .contains("NO other words");
    }

    @Test
    @DisplayName("sin subtítulo ni botón no se piden")
    void sinSubtituloNiBoton() {
        Datos sinExtras = new Datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, "CMRG", "s", "Obra segura", "", "",
                List.of(), 1, null);

        String prompt = PromptDeImagen.armar(sinExtras);

        assertThat(prompt).contains("HEADLINE: \"Obra segura\"").doesNotContain("SUBTITLE:").doesNotContain("BUTTON:");
    }

    @Test
    @DisplayName("los márgenes van en píxeles concretos, distintos para publicación e historia")
    void margenesEnPixeles() {
        String post = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, null));
        String historia = PromptDeImagen.armar(datos(Lienzo.HISTORIA, Layout.PHOTO_BOTTOM_BAND, 1, null));

        // Banda inferior: el texto va abajo, así que el recorte quita casi todo de ARRIBA.
        assertThat(post).contains("the top 205 and the bottom 51 pixels are cut")
                .contains("x=64..960, y=277..1413").contains("4:5");
        assertThat(historia).contains("x=130..894, y=240..1230").contains("9:16");
        assertThat(post).contains("1024x1536");
    }

    @Test
    @DisplayName("el recorte a 4:5 se quita de donde sobra: cada composición corta distinto")
    void recorteSegunLaComposicion() {
        assertThat(PromptDeImagen.cortesVerticales(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND))
                .containsExactly(205, 51);
        assertThat(PromptDeImagen.cortesVerticales(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_TOP_TITLE))
                .containsExactly(51, 205);
        assertThat(PromptDeImagen.cortesVerticales(Lienzo.CUATRO_QUINTOS, Layout.FRAMED_PHOTO))
                .containsExactly(128, 128);
        // Los demás lienzos no cortan a lo alto.
        assertThat(PromptDeImagen.cortesVerticales(Lienzo.HISTORIA, Layout.PHOTO_BOTTOM_BAND)).containsExactly(0, 0);
        assertThat(PromptDeImagen.cortesVerticales(Lienzo.CUADRADO, Layout.PHOTO_BOTTOM_BAND)).containsExactly(0, 0);

        String titulo = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_TOP_TITLE, 1, null));
        String marco = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.FRAMED_PHOTO, 1, null));
        assertThat(titulo).contains("y=123..1259");
        // Parejo, como siempre: 200..1336.
        assertThat(marco).contains("y=200..1336");
    }

    @Test
    @DisplayName("el rincón del logo cae donde el recorte lo deja: cambia con la composición")
    void logoSegunElRecorte() {
        // Se pega a 37 px del borde de arriba de la imagen YA recortada: en el lienzo, después del corte.
        assertThat(PromptDeImagen.zonaLogo(Posicion.TOP_LEFT, Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND))
                .contains("y=242..452");
        assertThat(PromptDeImagen.zonaLogo(Posicion.TOP_LEFT, Lienzo.CUATRO_QUINTOS, Layout.FRAMED_PHOTO))
                .contains("y=165..375");
        assertThat(PromptDeImagen.zonaLogo(Posicion.BOTTOM_LEFT, Lienzo.CUATRO_QUINTOS, Layout.PHOTO_TOP_TITLE))
                .contains("y=1073..1283");
    }

    @Test
    @DisplayName("el texto no se come la imagen: un cuarto a lo más, y el botón es una píldora compacta")
    void textoContenido() {
        String prompt = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, null));

        assertThat(prompt)
                .contains("no more than about a quarter of the image height")
                .contains("never a full-width bar")
                .contains("headline lines about 76 px tall")
                .contains("the panel covers only about a quarter of the image")
                .contains("about y=1150")
                .contains("at most two short lines");
    }

    @Test
    @DisplayName("cada composición se describe distinto")
    void composiciones() {
        String banda = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, null));
        String titulo = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_TOP_TITLE, 1, null));
        String marco = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.FRAMED_PHOTO, 1, null));

        assertThat(banda).contains("solid panel").contains("anchored to the bottom");
        assertThat(titulo).contains("soft dark gradient over the top third");
        assertThat(marco).contains("large rounded rectangle").contains("solid background in the primary brand color");
    }

    @Test
    @DisplayName("los colores de la marca van con sus códigos; sin logo se pide una paleta sobria")
    void colores() {
        String conLogo = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, Posicion.TOP_LEFT));
        String sinPaleta = PromptDeImagen.armar(new Datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, "CMRG", "s",
                "Obra", "", "", List.of(), 1, null));

        assertThat(conLogo).contains("#0B2A5B, #1FA34A").contains("brand colors, read from the logo");
        assertThat(sinPaleta).contains("restrained professional palette");
    }

    @Test
    @DisplayName("con logo se reserva un rincón en píxeles y se prohíbe dibujar logos")
    void espacioDelLogo() {
        String prompt = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, Posicion.TOP_LEFT));

        assertThat(prompt)
                .contains("at the top-left")
                .contains("will be placed there afterwards")
                .contains("Do not draw any logo");
        assertThat(PromptDeImagen.zonaLogo(Posicion.BOTTOM_CENTER, Lienzo.CUATRO_QUINTOS)).contains("at the bottom-center");
        assertThat(PromptDeImagen.zonaLogo(Posicion.TOP_RIGHT, Lienzo.HISTORIA)).contains("at the top-right");
    }

    @Test
    @DisplayName("sin logo no se reserva espacio pero tampoco se deja dibujar uno")
    void sinLogo() {
        String prompt = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, null));

        assertThat(prompt).doesNotContain("will be placed there afterwards").contains("do not draw any logo");
    }

    @Test
    @DisplayName("la foto es real y manda; las demás son solo contexto, no un collage")
    void fotos() {
        String una = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, null));
        String varias = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 3, null));
        String ninguna = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 0, null));

        assertThat(una).contains("Photo 1 is the hero").contains("REAL photographs").contains("Never add fog");
        assertThat(una).doesNotContain("context only");
        assertThat(varias).contains("context only").contains("do not make a collage");
        assertThat(ninguna).contains("No reference photo is given");
    }

    @Test
    @DisplayName("la lista de cosas a evitar incluye lo que salió mal en las pruebas reales")
    void evitar() {
        String prompt = PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, null));

        assertThat(prompt)
                .contains("AVOID:")
                .contains("no clock, no phone")
                .contains("cut-off letters")
                .contains("text touching an edge");
    }

    @Test
    @DisplayName("la escena del director va tal cual; sin ella hay una por defecto")
    void escena() {
        assertThat(PromptDeImagen.armar(datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, 1, null)))
                .contains("SCENE DIRECTION: Warm afternoon light.");
        Datos sinEscena = new Datos(Lienzo.CUATRO_QUINTOS, Layout.PHOTO_BOTTOM_BAND, "CMRG", " ", "Obra", "", "", List.of(), 1,
                null);
        assertThat(PromptDeImagen.armar(sinEscena)).contains("Natural daylight");
    }

    @Test
    @DisplayName("un código de composición desconocido cae a la banda inferior")
    void layoutDesconocido() {
        assertThat(Layout.de("collage_loco")).isEqualTo(Layout.PHOTO_BOTTOM_BAND);
        assertThat(Layout.de(null)).isEqualTo(Layout.PHOTO_BOTTOM_BAND);
        assertThat(Layout.de(" Framed_Photo ")).isEqualTo(Layout.FRAMED_PHOTO);
    }

    @Test
    @DisplayName("una versión cuadrada (LinkedIn) pide lienzo 1024x1024 y márgenes de un cuadrado")
    void cuadrado() {
        String prompt = PromptDeImagen.armar(datos(Lienzo.CUADRADO, Layout.PHOTO_BOTTOM_BAND, 1, Posicion.TOP_LEFT));

        assertThat(prompt)
                .contains("Square canvas of 1024x1024")
                .contains("1:1 square")
                .contains("x=80..944, y=80..944")
                .contains("about y=780")
                .doesNotContain("cut to 4:5");
        assertThat(PromptDeImagen.zonaLogo(Posicion.TOP_LEFT, Lienzo.CUADRADO)).contains("y=40..250");
        assertThat(PromptDeImagen.zonaLogo(Posicion.BOTTOM_RIGHT, Lienzo.CUADRADO)).contains("y=774..984");
    }
}

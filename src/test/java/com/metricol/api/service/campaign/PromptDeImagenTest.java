package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.campaign.CampaignImageService.Formato;
import com.metricol.api.service.campaign.PromptDeImagen.Datos;
import com.metricol.api.service.campaign.PromptDeImagen.Layout;
import com.metricol.api.service.campaign.SelloDeLogo.Posicion;

class PromptDeImagenTest {

    private static Datos datos(Formato formato, Layout layout, int fotos, Posicion logo) {
        return new Datos(formato, layout, "CMRG S.A. de C.V.", "Warm afternoon light.",
                "Montaje seguro y a tiempo", "Manzanillo, Colima", "Escríbenos por WhatsApp",
                List.of("#0B2A5B", "#1FA34A"), fotos, logo);
    }

    @Test
    @DisplayName("los textos van literales y entre comillas: la IA los copia, no los inventa")
    void textosLiterales() {
        String prompt = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, Posicion.TOP_LEFT));

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
        Datos sinExtras = new Datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, "CMRG", "s", "Obra segura", "", "",
                List.of(), 1, null);

        String prompt = PromptDeImagen.armar(sinExtras);

        assertThat(prompt).contains("HEADLINE: \"Obra segura\"").doesNotContain("SUBTITLE:").doesNotContain("BUTTON:");
    }

    @Test
    @DisplayName("los márgenes van en píxeles concretos, distintos para publicación e historia")
    void margenesEnPixeles() {
        String post = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, null));
        String historia = PromptDeImagen.armar(datos(Formato.STORY, Layout.PHOTO_BOTTOM_BAND, 1, null));

        assertThat(post).contains("x=64..960, y=200..1336").contains("4:5");
        assertThat(historia).contains("x=130..894, y=240..1230").contains("9:16");
        assertThat(post).contains("1024x1536");
    }

    @Test
    @DisplayName("cada composición se describe distinto")
    void composiciones() {
        String banda = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, null));
        String titulo = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_TOP_TITLE, 1, null));
        String marco = PromptDeImagen.armar(datos(Formato.POST, Layout.FRAMED_PHOTO, 1, null));

        assertThat(banda).contains("solid panel").contains("anchored to the bottom");
        assertThat(titulo).contains("soft dark gradient over the top third");
        assertThat(marco).contains("large rounded rectangle").contains("solid background in the primary brand color");
    }

    @Test
    @DisplayName("los colores de la marca van con sus códigos; sin logo se pide una paleta sobria")
    void colores() {
        String conLogo = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, Posicion.TOP_LEFT));
        String sinPaleta = PromptDeImagen.armar(new Datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, "CMRG", "s",
                "Obra", "", "", List.of(), 1, null));

        assertThat(conLogo).contains("#0B2A5B, #1FA34A").contains("brand colors, read from the logo");
        assertThat(sinPaleta).contains("restrained professional palette");
    }

    @Test
    @DisplayName("con logo se reserva un rincón en píxeles y se prohíbe dibujar logos")
    void espacioDelLogo() {
        String prompt = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, Posicion.TOP_LEFT));

        assertThat(prompt)
                .contains("at the top-left")
                .contains("will be placed there afterwards")
                .contains("Do not draw any logo");
        assertThat(PromptDeImagen.zonaLogo(Posicion.BOTTOM_CENTER, Formato.POST)).contains("at the bottom-center");
        assertThat(PromptDeImagen.zonaLogo(Posicion.TOP_RIGHT, Formato.STORY)).contains("at the top-right");
    }

    @Test
    @DisplayName("sin logo no se reserva espacio pero tampoco se deja dibujar uno")
    void sinLogo() {
        String prompt = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, null));

        assertThat(prompt).doesNotContain("will be placed there afterwards").contains("do not draw any logo");
    }

    @Test
    @DisplayName("la foto es real y manda; las demás son solo contexto, no un collage")
    void fotos() {
        String una = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, null));
        String varias = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 3, null));
        String ninguna = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 0, null));

        assertThat(una).contains("Photo 1 is the hero").contains("REAL photographs").contains("Never add fog");
        assertThat(una).doesNotContain("context only");
        assertThat(varias).contains("context only").contains("do not make a collage");
        assertThat(ninguna).contains("No reference photo is given");
    }

    @Test
    @DisplayName("la lista de cosas a evitar incluye lo que salió mal en las pruebas reales")
    void evitar() {
        String prompt = PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, null));

        assertThat(prompt)
                .contains("AVOID:")
                .contains("no clock, no phone")
                .contains("cut-off letters")
                .contains("text touching an edge");
    }

    @Test
    @DisplayName("la escena del director va tal cual; sin ella hay una por defecto")
    void escena() {
        assertThat(PromptDeImagen.armar(datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, 1, null)))
                .contains("SCENE DIRECTION: Warm afternoon light.");
        Datos sinEscena = new Datos(Formato.POST, Layout.PHOTO_BOTTOM_BAND, "CMRG", " ", "Obra", "", "", List.of(), 1,
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
}
